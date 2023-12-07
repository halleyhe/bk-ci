/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.dispatch.cron

import com.tencent.devops.common.client.Client
import com.tencent.devops.common.event.dispatcher.pipeline.mq.MeasureEventDispatcher
import com.tencent.devops.common.event.pojo.measure.DispatchJobMetricsData
import com.tencent.devops.common.event.pojo.measure.DispatchJobMetricsEvent
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.dispatch.dao.RunningJobsDao
import com.tencent.devops.dispatch.pojo.enums.JobQuotaVmType
import com.tencent.devops.dispatch.utils.redis.JobQuotaRedisUtils
import com.tencent.devops.process.api.service.ServicePipelineResource
import com.tencent.devops.project.api.service.ServiceProjectResource
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Component
class JobStatisticsScheduler @Autowired constructor(
    private val client: Client,
    private val dslContext: DSLContext,
    private val runningJobsDao: RunningJobsDao,
    private val jobQuotaRedisUtils: JobQuotaRedisUtils,
    private val measureEventDispatcher: MeasureEventDispatcher
) {
    /**
     * 每天定时刷新流水线Job运行数据
     */
    @Scheduled(cron = "0 0 2 * * ? ")
    fun saveJobStatisticsHistory() {
        val redisLock = jobQuotaRedisUtils.getJobStatisticsLock()
        try {
            val lockSuccess = redisLock.tryLock()
            if (lockSuccess) {
                logger.info("Start save jobStatistics")
                val theDate = LocalDateTime.now().format(dateTimeFormatter)
                val cursor = jobQuotaRedisUtils.getLastDayAllProjectConcurrency()
                val jobMetricsDataList = mutableListOf<DispatchJobMetricsData>()
                var counter = 0
                while (cursor.hasNext()) {
                    counter++
                    val keyValue = cursor.next()
                    val (projectId, jobType) = jobQuotaRedisUtils.parseProjectJobTypeConcurrencyKey(keyValue.key)
                    jobMetricsDataList.add(DispatchJobMetricsData(
                        theDate = theDate,
                        projectId = projectId,
                        productId = client.get(ServiceProjectResource::class).get(projectId).data?.productId.toString(),
                        jobType = jobType,
                        maxJobConcurrency = keyValue.value.toInt(),
                        sumJobCost = jobQuotaRedisUtils.getLastDayProjectRunJobTypeTime(
                            projectId,
                            JobQuotaVmType.valueOf(jobType)
                        )?.toInt() ?: 0
                    ))

                    if (counter % 100 == 0) {
                        println("Start save jobStatistics counter: $counter -> pushAndClear dataMap.")
                        pushJobStatistics(jobMetricsDataList)
                        jobMetricsDataList.clear()
                    }
                }

                // 最后再把剩余数据push
                pushJobStatistics(jobMetricsDataList)
            }
        } catch (e: Throwable) {
            logger.error("Save jobStatistics failed.", e)
        } finally {
            redisLock.unlock()
        }
    }

    /**
     * 清理超过7天的job记录，防止一直占用
     */
    @Scheduled(cron = "0 0 1 * * ?")
    fun clearTimeOutJobRecord() {
        logger.info("start to clear timeout job record")
        val redisLock = jobQuotaRedisUtils.getTimeoutJobLock()
        try {
            val lockSuccess = redisLock.tryLock()
            if (lockSuccess) {
                logger.info("<<< Clear Pipeline Quota Start >>>")
                doClear()
            } else {
                logger.info("<<< Clear Pipeline Quota Job Has Running, Do Not Start>>>")
            }
        } catch (e: Throwable) {
            logger.error("Clear pipeline quota exception:", e)
        } finally {
            redisLock.unlock()
        }
    }

    private fun doClear() {
        val timeoutJobs = runningJobsDao.getTimeoutRunningJobs(dslContext, TIMEOUT_DAYS)
        if (timeoutJobs.isNotEmpty) {
            timeoutJobs.filterNotNull().forEach {
                logger.info("delete timeout running job: ${it.projectId}|${it.buildId}|${it.vmSeqId}")
            }
        }
        runningJobsDao.clearTimeoutRunningJobs(dslContext, TIMEOUT_DAYS)
        logger.info("finish to clear timeout jobs, total:${timeoutJobs.size}")

        logger.info("Check pipeline running.")
        val runningJobs = runningJobsDao.getTimeoutRunningJobs(dslContext, CHECK_RUNNING_DAYS)
        if (runningJobs.isEmpty()) {
            return
        }

        try {
            runningJobs.filterNotNull().forEach {
                val isRunning = client.get(ServicePipelineResource::class).isRunning(projectId = it.projectId,
                    buildId = it.buildId,
                    channelCode = ChannelCode.BS).data
                    ?: false
                if (!isRunning) {
                    runningJobsDao.delete(
                        dslContext = dslContext,
                        buildId = it.buildId,
                        vmSeqId = it.vmSeqId,
                        executeCount = it.executeCount
                    )
                    logger.info("${it.buildId}|${it.vmSeqId} Pipeline not running, but runningJob history not deleted.")
                }
            }
        } catch (e: Throwable) {
            logger.error("Check pipeline running failed, msg: ${e.message}")
        }
    }

    private fun pushJobStatistics(jobMetricsDataList: List<DispatchJobMetricsData>) {
        measureEventDispatcher.dispatch(
            DispatchJobMetricsEvent(
                projectId = "",
                pipelineId = "",
                buildId = "",
                jobMetricsList = jobMetricsDataList
            )
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(JobStatisticsScheduler::class.java)

        private const val TIMEOUT_DAYS = 7L
        private const val CHECK_RUNNING_DAYS = 1L
        private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
