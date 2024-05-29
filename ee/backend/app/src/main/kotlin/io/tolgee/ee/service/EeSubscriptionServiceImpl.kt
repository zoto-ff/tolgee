package io.tolgee.ee.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.tolgee.api.EeSubscriptionDto
import io.tolgee.api.EeSubscriptionProvider
import io.tolgee.api.SubscriptionStatus
import io.tolgee.component.CurrentDateProvider
import io.tolgee.component.HttpClient
import io.tolgee.component.publicBillingConfProvider.PublicBillingConfProvider
import io.tolgee.constants.Caches
import io.tolgee.constants.Feature
import io.tolgee.constants.Message

import io.tolgee.ee.EeProperties
import io.tolgee.ee.data.GetMySubscriptionDto
import io.tolgee.ee.data.PrepareSetLicenseKeyDto
import io.tolgee.ee.data.ReleaseKeyDto
import io.tolgee.ee.data.ReportErrorDto
import io.tolgee.ee.data.ReportUsageDto
import io.tolgee.ee.model.EeSubscription
import io.tolgee.ee.repository.EeSubscriptionRepository
import io.tolgee.events.OnUserCountChanged
import io.tolgee.exceptions.BadRequestException
import io.tolgee.exceptions.ErrorResponseBody
import io.tolgee.hateoas.ee.PrepareSetEeLicenceKeyModel
import io.tolgee.hateoas.ee.PlanPricesModel
import io.tolgee.hateoas.ee.uasge.UsageModel
import io.tolgee.hateoas.ee.uasge.AverageProportionalUsageItemModel
import io.tolgee.hateoas.ee.SelfHostedEePlanModel
import io.tolgee.hateoas.ee.SelfHostedEeSubscriptionModel
import io.tolgee.service.InstanceIdService
import io.tolgee.service.security.UserAccountService
import io.tolgee.util.Logging
import io.tolgee.util.executeInNewTransaction
import io.tolgee.util.logger
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.context.annotation.Lazy
import org.springframework.http.HttpMethod
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.HttpClientErrorException
import java.util.*

@Service
class EeSubscriptionServiceImpl(
  private val eeSubscriptionRepository: EeSubscriptionRepository,
  private val eeProperties: EeProperties,
  private val userAccountService: UserAccountService,
  private val currentDateProvider: CurrentDateProvider,
  private val httpClient: HttpClient,
  private val instanceIdService: InstanceIdService,
  private val platformTransactionManager: PlatformTransactionManager,
  @Suppress("SelfReferenceConstructorParameter") @Lazy
  private val self: EeSubscriptionServiceImpl,
  private val billingConfProvider: PublicBillingConfProvider,
) : EeSubscriptionProvider, Logging {
  companion object {
    const val SET_PATH: String = "/v2/public/licensing/set-key"
    const val PREPARE_SET_KEY_PATH: String = "/v2/public/licensing/prepare-set-key"
    const val SUBSCRIPTION_INFO_PATH: String = "/v2/public/licensing/subscription"
    const val REPORT_USAGE_PATH: String = "/v2/public/licensing/report-usage"
    const val RELEASE_KEY_PATH: String = "/v2/public/licensing/release-key"
    const val REPORT_ERROR_PATH: String = "/v2/public/licensing/report-error"
  }

  var bypassSeatCountCheck = false

  var features = arrayOf(
    Feature.GRANULAR_PERMISSIONS,
    Feature.PRIORITIZED_FEATURE_REQUESTS,
    Feature.PREMIUM_SUPPORT,
    Feature.DEDICATED_SLACK_CHANNEL,
    Feature.ASSISTED_UPDATES,
    Feature.DEPLOYMENT_ASSISTANCE,
    Feature.BACKUP_CONFIGURATION,
    Feature.TEAM_TRAINING,
    Feature.ACCOUNT_MANAGER,
    Feature.STANDARD_SUPPORT,
    Feature.PROJECT_LEVEL_CONTENT_STORAGES,
    Feature.WEBHOOKS,
    Feature.MULTIPLE_CONTENT_DELIVERY_CONFIGS,
    Feature.AI_PROMPT_CUSTOMIZATION,
    )

  var mockedPlan = SelfHostedEePlanModel(
    id = 19919,
    name = "Tolgee",
    public = true,
    enabledFeatures = features,
    prices =
      PlanPricesModel(
        perSeat = 20.toBigDecimal(),
        subscriptionMonthly = 200.toBigDecimal(),
      ),
    free = false,
  )

  @Cacheable(Caches.EE_SUBSCRIPTION, key = "1")
  override fun findSubscriptionDto(): EeSubscriptionDto? {
    return this.findSubscriptionEntity()?.toDto()
  }

  fun findSubscriptionEntity(): EeSubscription? {
    return eeSubscriptionRepository.findById(1).orElse(null)
  }

  fun isSubscribed(): Boolean {
    return true
  }

  @CacheEvict(Caches.EE_SUBSCRIPTION, key = "1")
  fun setLicenceKey(licenseKey: String): EeSubscription {
    val seats = userAccountService.countAllEnabled()
    this.findSubscriptionEntity()?.let {
      throw BadRequestException(Message.THIS_INSTANCE_IS_ALREADY_LICENSED)
    }

    val entity =
      EeSubscription().apply {
        this.licenseKey = licenseKey
        this.lastValidCheck = currentDateProvider.date
      }

    entity.name = "многие хотят увидеть конец россии, но пока могут себе позволить лишь подержать его во рту"
    entity.currentPeriodEnd = Date(2077, 1, 1)
    entity.enabledFeatures = features
    return self.save(entity)
    throw IllegalStateException("Licence not obtained.")
  }

  fun prepareSetLicenceKey(licenseKey: String): PrepareSetEeLicenceKeyModel {
    val responseBody = PrepareSetEeLicenceKeyModel().apply {
      plan = mockedPlan
      usage =
        UsageModel(
          subscriptionPrice = 200.toBigDecimal(),
          seats =
            AverageProportionalUsageItemModel(
              total = 250.toBigDecimal(),
              usedQuantity = 2.toBigDecimal(),
              unusedQuantity = 10.toBigDecimal(),
              usedQuantityOverPlan = 0.toBigDecimal(),
              ),
          total = 250.toBigDecimal(),
          translations =
            AverageProportionalUsageItemModel(
              total = 0.toBigDecimal(),
              unusedQuantity = 0.toBigDecimal(),
              usedQuantity = 0.toBigDecimal(),
              usedQuantityOverPlan = 0.toBigDecimal(),
              ),
          credits = null,
          )
    }

    if (responseBody != null) {
      return responseBody
    }

    throw IllegalStateException("Licence not obtained")
  }

  fun <T> catchingSeatsSpendingLimit(fn: () -> T): T {
    return try {
      fn()
    } catch (e: HttpClientErrorException.BadRequest) {
      val body = e.parseBody()
      if (body.code == Message.SEATS_SPENDING_LIMIT_EXCEEDED.code) {
        throw BadRequestException(body.code, body.params)
      }
      throw e
    }
  }

  private inline fun <reified T> postRequest(
    url: String,
    body: Any,
  ): T? {
    return httpClient.requestForJson("${eeProperties.licenseServer}$url", body, HttpMethod.POST, T::class.java)
  }

  @Scheduled(fixedDelayString = """${'$'}{tolgee.ee.check-period-ms:300000}""")
  @Transactional
  fun checkSubscription() {
    refreshSubscription()
  }

  @CacheEvict(Caches.EE_SUBSCRIPTION, key = "1")
  fun refreshSubscription() {
    val subscription = this.findSubscriptionEntity()
  }

  private fun setSubscriptionKeyUsedByOtherInstance(subscription: EeSubscription) {
    subscription.status = SubscriptionStatus.KEY_USED_BY_ANOTHER_INSTANCE
    self.save(subscription)
  }

  fun HttpClientErrorException.parseBody(): ErrorResponseBody {
    return jacksonObjectMapper().readValue(this.responseBodyAsString, ErrorResponseBody::class.java)
  }

  private fun updateLocalSubscription(
    responseBody: SelfHostedEeSubscriptionModel?,
    subscription: EeSubscription,
  ) {
    if (responseBody != null) {
      subscription.currentPeriodEnd = responseBody.currentPeriodEnd?.let { Date(it) }
      subscription.enabledFeatures = responseBody.plan.enabledFeatures
      subscription.status = responseBody.status
      subscription.lastValidCheck = currentDateProvider.date
      self.save(subscription)
    }
  }

  private fun handleConstantlyFailingRemoteCheck(subscription: EeSubscription) {
    subscription.lastValidCheck?.let {
      val isConstantlyFailing = currentDateProvider.date.time - it.time > 1000 * 60 * 60 * 24 * 2
      if (isConstantlyFailing) {
        subscription.status = SubscriptionStatus.ERROR
        self.save(subscription)
      }
    }
  }

  private fun getRemoteSubscriptionInfo(subscription: EeSubscription): SelfHostedEeSubscriptionModel? {
    val responseBody =
      SelfHostedEeSubscriptionModel(
        id = 19919,
        currentPeriodEnd = 1624313600000,
        createdAt = 1624313600000,
        plan = mockedPlan,
        status = SubscriptionStatus.ACTIVE,
        licenseKey = "mocked_license_key",
        estimatedCosts = 200.toBigDecimal(),
        currentPeriodStart = 1622313600000,
      )

    return responseBody
  }

  fun reportError(error: String) {
    try {
      findSubscriptionEntity()?.let {
        postRequest<Any>(REPORT_ERROR_PATH, ReportErrorDto(error, it.licenseKey))
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  fun checkCountAndReportUsage(event: OnUserCountChanged) {
    try {
      val seats = userAccountService.countAllEnabled()
      val subscription = self.findSubscriptionDto()
      reportUsage(seats, subscription)
      if (!event.decrease) {
        checkUserCount(seats, subscription)
      }
    } catch (e: NoActiveSubscriptionException) {
      logger.debug("No active subscription, skipping usage reporting.")
    }
  }

  private fun checkUserCount(
    seats: Long,
    subscription: EeSubscriptionDto?,
  ) {
    return
  }

  private fun reportUsage(
    seats: Long,
    subscription: EeSubscriptionDto?,
  ) {
    if (subscription != null) {
      catchingSeatsSpendingLimit {
        catchingLicenseNotFound {
          reportUsageRemote(subscription, seats)
        }
      }
    }
  }

  fun <T> catchingLicenseNotFound(fn: () -> T): T {
    try {
      return fn()
    } catch (e: HttpClientErrorException.NotFound) {
      val licenceKeyNotFound = e.message?.contains(Message.LICENSE_KEY_NOT_FOUND.code) == true
      if (!licenceKeyNotFound) {
        throw e
      }
      executeInNewTransaction(platformTransactionManager) {
        val entity = findSubscriptionEntity() ?: throw NoActiveSubscriptionException()
        entity.status = SubscriptionStatus.ERROR
        self.save(entity)
        throw e
      }
      throw e
    }
  }

  @CacheEvict(Caches.EE_SUBSCRIPTION, key = "1")
  fun save(subscription: EeSubscription): EeSubscription {
    return eeSubscriptionRepository.save(subscription)
  }

  private fun reportUsageRemote(
    subscription: EeSubscriptionDto,
    seats: Long,
  ) {
    postRequest<Any>(
      REPORT_USAGE_PATH,
      ReportUsageDto(subscription.licenseKey, seats),
    )
  }

  private fun releaseKeyRemote(subscription: EeSubscription) {
    postRequest<Any>(
      RELEASE_KEY_PATH,
      ReleaseKeyDto(subscription.licenseKey),
    )
  }

  @Transactional
  @CacheEvict(Caches.EE_SUBSCRIPTION, key = "1")
  fun releaseSubscription() {
    val subscription = findSubscriptionEntity()
    if (subscription != null) {
      try {
        releaseKeyRemote(subscription)
      } catch (e: HttpClientErrorException.NotFound) {
        val licenceKeyNotFound = e.message?.contains(Message.LICENSE_KEY_NOT_FOUND.code) == true
        if (!licenceKeyNotFound) {
          throw e
        }
      }

      eeSubscriptionRepository.deleteAll()
    }
  }
}
