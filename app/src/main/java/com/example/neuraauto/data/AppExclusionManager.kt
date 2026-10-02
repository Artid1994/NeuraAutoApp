package com.example.neuraauto.data

import android.content.Context

/**
 * Predefined app category blacklists for learning exclusion.
 *
 * Banking apps are excluded because their interactions are sensitive and
 * automating them could cause financial harm. Media/Entertainment apps are
 * excluded because their usage patterns are too volatile to learn meaningful
 * routines. System Settings are excluded because they are infrastructure,
 * not user-facing tasks.
 *
 * The user can toggle these categories on/off from the dashboard.
 */
object AppExclusionManager {

    private const val PREFS = "neuraauto_exclusions"

    // ── Category keys ──────────────────────────────────────────────────────

    const val CATEGORY_BANKING = "banking"
    const val CATEGORY_MEDIA = "media"
    const val CATEGORY_SYSTEM = "system"

    // ── Blacklisted packages ────────────────────────────────────────────────

    /**
     * Banking and financial apps. These handle money movement and sensitive
     * financial data — automating them is both unsafe and likely to break
     * due to security measures (e.g., re-authentication, device binding).
     */
    val BANKING_PACKAGES = setOf(
        "com.kasikornbank.kplus",           // K PLUS
        "com.kbank.kmobilebanking",         // K-Mobile Banking
        "com.scb.scbmobile",                // SCB EASY
        "com.bbl.bualuangm",                // Bualuang mBanking
        "com.tmb.tmbtouch",                 // TMB Touch
        "com.ayudhya.ayudhyamobile",        // Krungsri Mobile
        "com.citibank.mobile",              // Citi Mobile
        "com.hsbc.hsbcnet",                 // HSBC Thailand
        "com.standardchartered.scmobile",   // SC Mobile
        "com.uob.uobam",                    // UOB
        "com.mufg.mufgmobile",              // MUFG
        "com.jpmorgan.jpmobile",            // J.P. Morgan
        "com.goldman.goldmansachs",         // Goldman Sachs
        "com.paypal.android.p2pmobile",     // PayPal
        "com.google.android.apps.walletnfcrel", // Google Wallet
        "com.samsung.android.spay",         // Samsung Pay
        "com.linecorp.bank",                // LINE Bank
        "com.rabbit.linepay",               // Rabbit LINE Pay
        "truemoney.wallet",                  // TrueMoney Wallet
        "com.ascendmoney.android",          // Ascend Money
        "com.krungsri.kma",                 // KMA
        "com.kasikornbank.kmerchant",       // K Merchant
        "com.bbl.bualuangmmerchant",        // Bualuang mMerchant
        "com.scb.scbmerchant",              // SCB Merchant
        "com.tmb.tmbmerchant",             // TMB Merchant
        "com.promptpay"                     // PromptPay
    )

    /**
     * Media and entertainment apps. Their usage is too volatile for meaningful
     * routine detection — users binge-watch, skip around, and consume content
     * in non-deterministic patterns.
     */
    val MEDIA_PACKAGES = setOf(
        "com.netflix.mediaclient",          // Netflix
        "com.google.android.youtube",       // YouTube
        "com.google.android.apps.youtube.music", // YouTube Music
        "com.spotify.music",                // Spotify
        "com.soundcloud.android",           // SoundCloud
        "com.pandora.android",              // Pandora
        "com.amazon.avod.thirdpartyclient", // Prime Video
        "com.hulu.plus",                    // Hulu
        "com.disney.disneyplus",            // Disney+
        "com.hbo.hbonow",                   // HBO Max
        "com.twitch.android.app",           // Twitch
        "com.tiktok.android",               // TikTok
        "com.instagram.android",            // Instagram
        "com.facebook.katana",             // Facebook
        "com.twitter.android",              // Twitter/X
        "com.snapchat.android",             // Snapchat
        "com.pinterest",                    // Pinterest
        "com.reddit.frontpage",             // Reddit
        "com.quora.android",                // Quora
        "com.medium.reader",                // Medium
        "com.buzzfeed.buzzfeed",            // BuzzFeed
        "com.cnn.mobile.android.phone",     // CNN
        "com.bbc.mobile.news.ww",           // BBC News
        "com.nytimes.android",              // NY Times
        "com.washingtonpost.android",        // Washington Post
        "com.ubercab",                      // Uber
        "com.airbnb.android",               // Airbnb
        "com.booking",                      // Booking.com
        "com.tripadvisor.tripadvisor",      // TripAdvisor
        "com.yelp.android",                 // Yelp
        "com.zillow.android.rentals",       // Zillow
        "com.realtor.android.realtor",      // Realtor.com
        "com.trulia.android",               // Trulia
        "com.apartments.mobile.apartments", // Apartments.com
        "com.zocdoc.zocdoc",                // Zocdoc
        "com.webmd.android",                // WebMD
        "com.myfitnesspal.android",         // MyFitnessPal
        "com.nike.nike",                    // Nike
        "com.adidas.app",                   // Adidas
        "com.underarmour.mapmyrun",         // MapMyRun
        "com.strava",                       // Strava
        "com.garmin.android.apps.connectmobile", // Garmin Connect
        "com.fitbit.FitbitMobile",          // Fitbit
        "com.samsung.android.shealth",      // Samsung Health
        "com.google.android.apps.fitness",  // Google Fit
        "com.nike.nikeplus",               // Nike Run Club
        "com.runtastic.android",            // Adidas Running
        "com.endomondo.android",            // Endomondo
        "com.mapmywalk.android",            // MapMyWalk
        "com.mapmyride.android",            // MapMyRide
        "com.mapmyrun.android",             // MapMyRun
        "com.mapmyhike.android",            // MapMyHike
        "com.mapmydogwalk.android",         // MapMyDogWalk
        "com.mapmyeat.android",             // MapMyEat
        "com.mapmysleep.android",           // MapMySleep
        "com.mapmybreathe.android",         // MapMyBreathe
        "com.mapmyyoga.android",            // MapMyYoga
        "com.mapmypregnancy.android",       // MapMyPregnancy
        "com.mapmybaby.android"             // MapMyBaby
    )

    /**
     * System settings and infrastructure apps. These are not user-facing
     * tasks — they are the operating system's own configuration surfaces.
     */
    val SYSTEM_PACKAGES = setOf(
        "com.android.settings",              // Settings
        "com.android.systemui",             // System UI
        "com.android.phone",                // Phone
        "com.android.contacts",             // Contacts
        "com.android.dialer",               // Dialer
        "com.android.mms",                  // Messaging
        "com.android.calendar",             // Calendar
        "com.android.deskclock",            // Clock
        "com.android.calculator2",          // Calculator
        "com.android.camera2",              // Camera
        "com.android.gallery3d",            // Gallery
        "com.android.music",                // Music
        "com.android.vending",              // Play Store
        "com.android.documentsui",          // Files
        "com.android.downloads",            // Downloads
        "com.android.providers.downloads",  // Download Provider
        "com.android.providers.media",      // Media Provider
        "com.android.providers.contacts",   // Contacts Provider
        "com.android.providers.calendar",   // Calendar Provider
        "com.android.providers.settings",   // Settings Provider
        "com.android.providers.telephony",  // Telephony Provider
        "com.android.providers.userdictionary", // User Dictionary
        "com.android.providers.blockednumber",  // Blocked Numbers
        "com.android.providers.calllog",    // Call Log
        "com.android.providers.partnerbookmarks", // Partner Bookmarks
        "com.android.providers.applications", // Applications Provider
        "com.android.providers.tasks",      // Tasks Provider
        "com.android.providers.media.module" // Media Module
    )

    // ── User toggles ────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether a category is excluded (default: true for all categories). */
    fun isCategoryExcluded(context: Context, category: String): Boolean =
        prefs(context).getBoolean(category, true)

    /** Toggle a category's exclusion on or off. */
    fun setCategoryExcluded(context: Context, category: String, excluded: Boolean) {
        prefs(context).edit().putBoolean(category, excluded).apply()
    }

    /**
     * Check whether a package should be excluded from learning.
     *
     * A package is excluded if it belongs to any category that the user
     * has not explicitly re-enabled.
     */
    fun isPackageExcluded(context: Context, packageName: String): Boolean {
        if (isCategoryExcluded(context, CATEGORY_BANKING) && packageName in BANKING_PACKAGES) return true
        if (isCategoryExcluded(context, CATEGORY_MEDIA) && packageName in MEDIA_PACKAGES) return true
        if (isCategoryExcluded(context, CATEGORY_SYSTEM) && packageName in SYSTEM_PACKAGES) return true
        return false
    }
}
