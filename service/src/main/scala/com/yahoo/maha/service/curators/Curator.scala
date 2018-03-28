// Copyright 2017, Yahoo Holdings Inc.
// Licensed under the terms of the Apache License 2.0. Please see LICENSE file in project root for terms.
package com.yahoo.maha.service.curators

import java.util.concurrent.Callable

import com.yahoo.maha.core.RequestModelResult
import com.yahoo.maha.core.bucketing.BucketParams
import com.yahoo.maha.core.request.ReportingRequest
import com.yahoo.maha.parrequest.future.ParRequest
import com.yahoo.maha.parrequest.{Either, GeneralError, ParCallable, Right}
import com.yahoo.maha.service.error.MahaServiceBadRequestException
import com.yahoo.maha.service.utils.MahaRequestLogHelper
import com.yahoo.maha.service.{MahaService, RequestResult}
import grizzled.slf4j.Logging

import scala.util.Try

case class CuratorResult(requestResultTry: Try[RequestResult])

trait Curator extends Ordered[Curator] {
  val name: String
  val level: Int
  val priority: Int
  def process(registryName: String,
              bucketParams: BucketParams,
              reportingRequest: ReportingRequest,
              mahaService: MahaService,
              mahaRequestLogHelper: MahaRequestLogHelper) : ParRequest[CuratorResult]
  def compare(that: Curator) = {
    if(this.level == that.level) {
      Integer.compare(this.priority, that.priority)
    } else Integer.compare(this.level, that.level)
  }
}

object DefaultCurator {
  val name: String = "default"
}

class DefaultCurator extends Curator with Logging {

  override val name: String = DefaultCurator.name
  override val level: Int = 0
  override val priority: Int = 0

  override def process(registryName: String,
                       bucketParams: BucketParams,
                       reportingRequest: ReportingRequest,
                       mahaService: MahaService,
                       mahaRequestLogHelper: MahaRequestLogHelper): ParRequest[CuratorResult] = {

    val registryConfig = mahaService.getMahaServiceConfig.registry.get(registryName).get
    val parallelServiceExecutor = registryConfig.parallelServiceExecutor
    val parRequestLabel = "processDefaultCurator"

    val parRequest = parallelServiceExecutor.parRequestBuilder[CuratorResult].setLabel(parRequestLabel).
      setParCallable(ParCallable.from[Either[GeneralError, CuratorResult]](
        new Callable[Either[GeneralError, CuratorResult]](){
          override def call(): Either[GeneralError, CuratorResult] = {

            val requestModelResultTry: Try[RequestModelResult] = mahaService.generateRequestModel(registryName, reportingRequest, bucketParams , mahaRequestLogHelper)

            if(requestModelResultTry.isFailure) {
              val message = requestModelResultTry.failed.get.getMessage
              mahaRequestLogHelper.logFailed(message)
              return GeneralError.either[CuratorResult](parRequestLabel, message, new MahaServiceBadRequestException(message))
            }

            val requestResultTry = mahaService.processRequestModel(registryName, requestModelResultTry.get.model, mahaRequestLogHelper)
            return new Right[GeneralError, CuratorResult](CuratorResult(requestResultTry))
          }
        }
      )).build()
    parRequest
  }

}
