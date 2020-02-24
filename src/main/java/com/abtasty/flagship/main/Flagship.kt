package com.abtasty.flagship.main

import android.content.Context
import com.abtasty.flagship.api.ApiManager
import com.abtasty.flagship.api.BucketingManager
import com.abtasty.flagship.api.HitBuilder
import com.abtasty.flagship.database.DatabaseManager
import com.abtasty.flagship.model.Modification
import com.abtasty.flagship.utils.FlagshipContext
import com.abtasty.flagship.utils.FlagshipPrivateContext
import com.abtasty.flagship.utils.Logger
import com.abtasty.flagship.utils.Utils
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

/**
 * Flagship main class
 */
class Flagship {

    /**
     * LogMode Types
     */
    enum class LogMode {
        NONE, ALL, ERRORS, VERBOSE
    }

    /**
     * Set the SDK Mode in client side or server Side
     */
    enum class Mode {

        /**
         * Server-side mode - The server will apply targeting and allocate campaigns. (Default)
         */
        DECISION_API,
        /**
         * Client-side mode - The mobile will apply targeting and allocate campaigns.
         */
        BUCKETING
    }

    /**
     * Builder is a builder class to initialize the Flaghip SDK.
     *
     * @param appContext applicationContext
     * @param envId key provided by ABTasty
     */
    class Builder(private var appContext: Context, private var envId: String) {

        private var ready: (() -> Unit)? = null

        /**
         * Start Flagship SDK in BUCKETING mode (client-side) or in DECISION_API mode (server-side). Default is DECISION_API
         *
         * @param mode
         * @return Flagship
         */
        fun withFlagshipMode(mode: Mode): Builder {
            Companion.mode = mode
            return this
        }

        /**
         * Set a code to apply when the SDK has finished to initialize.
         * @param lambda code to apply
         * @return Flagship
         */
        fun withReadyCallback(lambda: (() -> Unit)): Builder {
            ready = lambda
            return this
        }

        /**
         * Set an id for identifying the current visitor
         * @return Flagship
         */
        fun withVisitorId(visitorId: String = ""): Builder {
            Companion.setVisitorId(visitorId)
            return this
        }


        /**
         * Enable logs of the SDK
         */
        fun withLogEnabled(mode: LogMode): Builder {
            Logger.logMode = mode
            return this
        }


        /**
         * Start the Flagship SDK
         */
        fun start() {
            start(appContext, envId, ready)
        }
    }

    companion object {

        internal const val VISITOR_ID = "visitorId"

        internal var clientId: String? = null

        internal var visitorId: String = ""

        internal var mode = Mode.DECISION_API

        @PublishedApi
        internal var context = HashMap<String, Any>()

        @PublishedApi
        internal var modifications = HashMap<String, Modification>()

        internal var sessionStart: Long = -1

        internal var panicMode = false

        internal var ready = false

        internal var isFirstInit: Boolean? = null

        /**
         * Initialize the flagship SDK
         *
         * @param appContext application context
         * @param envId key provided by ABTasty
         * @return Builder
         **/
        fun init(appContext: Context, envId: String): Builder {
            return Builder(appContext, envId)
        }

        /**
         * Initialize the flagship SDK
         *
         * @param appContext application context
         * @param envId key provided by ABTasty
         * @param visitorId (optional) set an id for identifying the current visitor
         * @param ready (optional) to execute when the SDK is ready
         */
        @JvmOverloads
        internal fun start(
            appContext: Context,
            envId: String,
            ready: (() -> Unit)? = null
        ) {

            this.clientId = envId
            this.visitorId =
                if (visitorId.isNotEmpty()) visitorId else Utils.genVisitorId(appContext)
            sessionStart = System.currentTimeMillis()
            ApiManager.cacheDir = appContext.cacheDir
            isFirstInit = Utils.isFirstInit(appContext)

            modifications.clear()
            context.clear()

            Utils.loadDeviceContext(appContext.applicationContext)
            DatabaseManager.getInstance().init(appContext.applicationContext)
            ApiManager.getInstance().fireOfflineHits()
            when (mode) {
                Mode.DECISION_API -> syncCampaignModifications(ready)
                Mode.BUCKETING -> BucketingManager.startBucketing(ready)
            }
        }

        /**
         * Set an id for identifying the current visitor. This will clear any previous modifications and visitor context.
         *
         * @param visitorId id of the current visitor
         */
        fun setVisitorId(visitorId: String) {
            if (!panicMode) {
                this.visitorId = visitorId
                modifications.clear()
                context.clear()
                Utils.loadDeviceContext(null)
                DatabaseManager.getInstance().loadModifications()
            }
        }

        /**
         * This function updates the visitor context value matching the given key.
         * A new context value associated with this key will be created if there is no matching.
         *
         * @param key key to associate with the following value
         * @param value new context value
         * @param sync (optional : null by default) If a lambda is passed as parameter : it will automatically update the campaigns modifications.
         * Then this lambda will be invoked when finished.
         * You also have the possibility to update it manually by calling syncCampaignModifications()
         * @see syncCampaignModifications()
         */
        @JvmOverloads
        fun updateContext(key: String, value: Number, sync: (() -> (Unit))? = null) {
            updateContextValue(key, value, sync)
        }

        /**
         * This function updates the visitor context value matching the given key.
         * A new context value associated with this key will be created if there is no matching.
         *
         * @param key key to associate with the following value
         * @param value new context value
         * @param sync (optional : null by default) If a lambda is passed as parameter : it will automatically update the campaigns modifications.
         * Then this lambda will be invoked when finished.
         * You also have the possibility to update it manually by calling syncCampaignModifications()
         * @see syncCampaignModifications()
         */
        @JvmOverloads
        fun updateContext(key: String, value: String, sync: (() -> (Unit))? = null) {
            updateContextValue(key, value, sync)
        }

        /**
         * This function updates the visitor context value matching the given key.
         * A new context value associated with this key will be created if there is no matching.
         *
         * @param key key to associate with the following value
         * @param value new context value
         * @param sync (optional : null by default) If a lambda is passed as parameter : it will automatically update the campaigns modifications.
         * Then this lambda will be invoked when finished.
         * You also have the possibility to update it manually by calling syncCampaignModifications()
         * @see syncCampaignModifications()
         */
        @JvmOverloads
        fun updateContext(key: String, value: Boolean, sync: (() -> (Unit))? = null) {
            updateContextValue(key, value, sync)
        }

        /**
         * This function updates the visitor context value matching the given key.
         * A new context value associated with this key will be created if there is no matching.
         *
         * @param key Flagship context key to associate with the following value
         * @param value new context value
         * @param sync (optional : null by default) If a lambda is passed as parameter, it will automatically update the modifications
         * from the server for all the campaigns with the updated current context then this lambda will be invoked when finished.
         * You also have the possibility to update it manually : syncCampaignModifications()
         */
        @JvmOverloads
        fun updateContext(key: FlagshipContext, value: Any, sync: (() -> (Unit))? = null) {
            if (key.checkValue(value))
                updateContextValue(key.key, value, sync)
            else
                Logger.e(
                    Logger.TAG.CONTEXT,
                    "updateContext $key doesn't have the expected value type: $value."
                )
        }


        /**
         * This function updates the visitor context value matching the given key.
         * A new context value associated with this key will be created if there is no matching.
         *
         * @param key key to associate with the following value
         * @param value new context value
         * @param sync (optional : null by default) If a lambda is passed as parameter : it will automatically update the campaigns modifications.
         * Then this lambda will be invoked when finished.
         * You also have the possibility to update it manually by calling syncCampaignModifications()
         * @see syncCampaignModifications()
         */
        @JvmOverloads
        fun updateContext(values: HashMap<String, Any>, sync: (() -> (Unit))? = null) {
            if (!panicMode) {
                for (p in values) {
                    updateContextValue(p.key, p.value)
                }
                if (ready && sync != null)
                    syncCampaignModifications(sync)
            }
        }

        @JvmOverloads
        private fun updateContextValue(
            key: String,
            value: Any,
            sync: (() -> (Unit))? = null
        ) {
            if (!panicMode) {
                when (true) {
                    (FlagshipPrivateContext.keys().contains(key)) -> {
                        Logger.e(
                            Logger.TAG.CONTEXT,
                            "Context Update : Your data \"$key\" is reserved and cannot be modified."
                        )
                    }
                    (value is Number || value is Boolean || value is String) -> context[key] = value
                    else -> {
                        Logger.e(
                            Logger.TAG.CONTEXT,
                            "Context update : Your data \"$key\" is not a type of NUMBER, BOOLEAN or STRING"
                        )
                    }
                }
                if (ready && sync != null)
                    syncCampaignModifications(sync)
            }
        }

        /**
         * This function clear all the visitor context values
         */
        @JvmOverloads
        fun clearContextValues() {
            context.clear()
        }

        /**
         * Get the campaign modification value matching the given key. Use syncCampaignModifications beforehand,
         * in order to update all the modifications from the server.
         *
         * @param key key associated with the modification
         * @param default default value returned when the key doesn't match any modification value.
         * @param activate (false by default) Set this param to true to automatically report on our server :
         * the current visitor has seen this modification. You also have the possibility to do it afterward
         * by calling activateModification().
         * @see com.abtasty.flagship.main.Flagship.syncCampaignModifications
         * @see com.abtasty.flagship.main.Flagship.activateModification
         */
        @JvmOverloads
        fun getModification(key: String, default: Int, activate: Boolean = false): Int {
            return getFlagshipModification(key, default, activate)
        }

        /**
         * Get the campaign modification value matching the given key. Use syncCampaignModifications beforehand,
         * in order to update all the modifications from the server.
         *
         * @param key key associated with the modification
         * @param default default value returned when the key doesn't match any modification value.
         * @param activate (false by default) Set this param to true to automatically report on our server :
         * the current visitor has seen this modification. You also have the possibility to do it afterward
         * by calling activateModification().
         * @see com.abtasty.flagship.main.Flagship.syncCampaignModifications
         * @see com.abtasty.flagship.main.Flagship.activateModification
         */
        @JvmOverloads
        fun getModification(key: String, default: Float, activate: Boolean = false): Float {
            return getFlagshipModification(key, default, activate)
        }

        /**
         * Get the campaign modification value matching the given key. Use syncCampaignModifications beforehand,
         * in order to update all the modifications from the server.
         *
         * @param key key associated with the modification
         * @param default default value returned when the key doesn't match any modification value.
         * @param activate (false by default) Set this param to true to automatically report on our server :
         * the current visitor has seen this modification. You also have the possibility to do it afterward
         * by calling activateModification().
         * @see com.abtasty.flagship.main.Flagship.syncCampaignModifications
         * @see com.abtasty.flagship.main.Flagship.activateModification
         */
        @JvmOverloads
        fun getModification(key: String, default: String, activate: Boolean = false): String {
            return getFlagshipModification(key, default, activate)
        }


        /**
         * Get the campaign modification value matching the given key. Use syncCampaignModifications beforehand,
         * in order to update all the modifications from the server.
         *
         * @param key key associated with the modification
         * @param default default value returned when the key doesn't match any modification value.
         * @param activate (false by default) Set this param to true to automatically report on our server :
         * the current visitor has seen this modification. You also have the possibility to do it afterward
         * by calling activateModification().
         * @see com.abtasty.flagship.main.Flagship.syncCampaignModifications
         * @see com.abtasty.flagship.main.Flagship.activateModification
         */
        @JvmOverloads
        fun getModification(key: String, default: Boolean, activate: Boolean = false): Boolean {
            return getFlagshipModification(key, default, activate)
        }

        /**
         * Get the campaign modification value matching the given key. Use syncCampaignModifications beforehand,
         * in order to update all the modifications from the server.
         *
         * @param key key associated with the modification
         * @param default default value returned when the key doesn't match any modification value.
         * @param activate (false by default) Set this param to true to automatically report on our server :
         * the current visitor has seen this modification. You also have the possibility to do it afterward
         * by calling activateModification().
         * @see com.abtasty.flagship.main.Flagship.syncCampaignModifications
         * @see com.abtasty.flagship.main.Flagship.activateModification
         */
        @JvmOverloads
        fun getModification(key: String, default: Double, activate: Boolean = false): Double {
            return getFlagshipModification(key, default, activate)
        }

        /**
         * Get the campaign modification value matching the given key. Use syncCampaignModifications beforehand,
         * in order to update all the modifications from the server.
         *
         * @param key key associated with the modification
         * @param default default value returned when the key doesn't match any modification value.
         * @param activate (false by default) Set this param to true to automatically report on our server :
         * the current visitor has seen this modification. You also have the possibility to do it afterward
         * by calling activateModification().
         * @see com.abtasty.flagship.main.Flagship.syncCampaignModifications
         * @see com.abtasty.flagship.main.Flagship.activateModification
         */
        @JvmOverloads
        fun getModification(key: String, default: Long, activate: Boolean = false): Long {
            return getFlagshipModification(key, default, activate)
        }


        private inline fun <reified T> getFlagshipModification(
            key: String,
            default: T,
            report: Boolean = false
        ): T {
            if (!panicMode) {
                return try {
                    val modification = modifications[key]
                    modification?.let {
                        val variationGroupId = modification.variationGroupId
                        val variationId = modification.variationId
                        val value = modification.value
                        (value as? T)?.let {
                            if (report)
                                ApiManager.getInstance().sendActivationRequest(
                                    variationGroupId,
                                    variationId
                                )
                            it
                        } ?: default
                    } ?: default
                } catch (e: Exception) {
                    Logger.e(
                        Logger.TAG.PARSING,
                        "Flagship.getValue \"$key\" is missing or types are different"
                    )
                    default
                }
            } else return default
        }

        /**
         * When the SDK is set with DECISION_API mode :
         * This function will call the decision api and update all the campaigns modifications from the server according to the user context.
         * If the SDK is set with BUCKETING mode :
         * This function will re-apply targeting and update all the campaigns modifications from the server according to the user context.
         *
         * @param lambda Lambda to be invoked when the SDK has finished to update the modifications from the server.
         * @param campaignCustomId (optional) Specify a campaignId to get only its modifications. Set an empty string to get all campaigns modifications (by default).
         *
         */
        @JvmOverloads
        fun syncCampaignModifications(
            lambda: (() -> (Unit))? = null,
            campaignCustomId: String = ""
        ) {
//            if (mode == Mode.DECISION_API) {
//                GlobalScope.async {
//                    if (!panicMode) {
//                        ApiManager.getInstance().sendCampaignRequest(campaignCustomId, context)
//                        ready = true
//                        lambda?.let { it() }
//                    }
//                }
//            } else
//                BucketingManager.syncBucketModifications(lambda)
            GlobalScope.async {
                if (mode == Mode.DECISION_API) {
                    if (!panicMode) {
                        ApiManager.getInstance().sendCampaignRequest(campaignCustomId, context)
                        ready = true
                        lambda?.let { it() }
                    }
                } else
                    BucketingManager.syncBucketModifications(lambda)
                Logger.v(Logger.TAG.SYNC, "current context : $context")
                Logger.v(Logger.TAG.SYNC, "current modifications : $modifications")
            }
        }

        @Synchronized
        internal fun updateModifications(values: HashMap<String, Modification>) {
            modifications.putAll(values)
        }

        @Synchronized
        internal fun resetModifications(values: HashMap<String, Modification>) {
            for (v in values) {
                modifications.remove(v.key)
            }
        }


        /**
         * This function allows you to report that a visitor has seen a modification to our servers
         *
         * @param key key which identifies the modification
         */
        fun activateModification(key: String) {
            if (!panicMode)
                getFlagshipModification(key, Any(), true)
        }

        /**
         * This function allows you to send tracking events on our servers such as Transactions, page views, clicks ...
         *
         * @param hit Hit to send
         * @see com.abtasty.flagship.api.Hit.Page
         * @see com.abtasty.flagship.api.Hit.Event
         * @see com.abtasty.flagship.api.Hit.Transaction
         * @see com.abtasty.flagship.api.Hit.Item
         *
         */
        fun <T> sendTracking(hit: HitBuilder<T>) {
            if (!panicMode)
                ApiManager.getInstance().sendHitTracking(hit)
        }


        /********************* DEPRECATIONS 1.1.0 - ***********************/

        /**
         *
         *
         * Initialize the flagship SDK @Deprecated
         *
         * Use the 'init' method and the returning 'Builder' instead
         *
         * @param appContext application context
         * @param envId key provided by ABTasty
         * @param visitorId (optional) set an id for identifying the current visitor
         */
        @Deprecated(
            message = "Use the 'init' method and the returning 'Builder' instead",
            replaceWith = ReplaceWith(
                "Flagship.init(appContext, envId)" +
                        "\n.withVisitorId(visitorId)" +
                        "\n.start()"
            )
        )
        @JvmOverloads
        fun start(appContext: Context, envId: String, visitorId: String) {
            init(appContext, envId)
                .withVisitorId(visitorId)
                .start()
        }

        /**
         * Enable logs of the SDK @Deprecated
         *
         * Use the Builder returned by the 'init' method in order to enabled the logs
         */
        @Deprecated(message = "Use the builder returned by the 'init method in order to enabled the logs")
        @JvmOverloads
        fun enableLog(mode: LogMode) {
            Logger.logMode = mode
        }

        /**
         * This function calls the decision api and updates all the campaigns modification from the server according to the user context. @Deprecated
         *
         * @param campaignCustomId (optional) Specify a campaignId to get its modifications. All campaigns by default.
         * @param lambda Lambda to be invoked when the SDK has finished to update the modifications from the server.
         *
         */
        @Deprecated(
            message = "Use `syncCampaignModifications(lambda, campaignCustomId` instead.",
            replaceWith = ReplaceWith("Flagship.syncCampaignModifications(lambda, campaignCustomId)")
        )
        @JvmOverloads
        fun syncCampaignModifications(
            campaignCustomId: String = "",
            lambda: () -> (Unit) = {}
        ): Deferred<Unit> {
            return GlobalScope.async { syncCampaignModifications(lambda, campaignCustomId) }
        }
    }
}