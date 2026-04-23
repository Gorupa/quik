/*
 * Copyright (C) 2024 QUIK Contributors
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 *
 * ──────────────────────────────────────────────────────────────────────────
 * SmsCategorizer
 *
 * Classifies SMS/MMS conversations into four categories using keyword and
 * pattern matching on the most recent message body. No network calls, no ML
 * model — pure deterministic logic that runs on-device in O(n) time.
 *
 * Categories
 * ──────────
 *  PERSONAL     — conversations with known contacts (no keywords needed)
 *  OTP          — one-time passwords, verification codes, login alerts
 *  TRANSACTIONAL — bank alerts, payment receipts, delivery tracking
 *  PROMOTIONAL  — offers, discounts, marketing, newsletters
 *
 * Usage (from a ViewModel or Repository)
 * ───────────────────────────────────────
 *  val category = SmsCategorizer.categorize(conversation)
 *
 *  // Filter a list of conversations
 *  val otpOnly = conversations.filter {
 *      SmsCategorizer.categorize(it) == SmsCategory.OTP
 *  }
 *
 * Contributor: @Gorupa — feature proposal & initial keyword corpus
 * ──────────────────────────────────────────────────────────────────────────
 */
package dev.octoshrimpy.quik.util

import dev.octoshrimpy.quik.model.Conversation

// ── Category enum ─────────────────────────────────────────────────────────

enum class SmsCategory {
    /** Conversation with a known contact — classified first, before keyword checks. */
    PERSONAL,

    /** One-time passwords, verification codes, login/security alerts. */
    OTP,

    /**
     * Bank alerts, payment confirmations, transaction notifications,
     * delivery and order updates.
     */
    TRANSACTIONAL,

    /**
     * Promotional offers, discount codes, newsletters, marketing blasts,
     * subscription offers.
     */
    PROMOTIONAL,
}

// ── Categorizer ───────────────────────────────────────────────────────────

object SmsCategorizer {

    // ── OTP keywords and patterns ──────────────────────────────────────────
    private val OTP_KEYWORDS = setOf(
        "otp", "one-time", "one time", "passcode", "verification code",
        "verify", "verification", "your code", "auth code", "authentication",
        "login code", "sign-in code", "sign in code", "security code",
        "access code", "confirmation code", "temporary password", "temp pass",
        "expires in", "valid for", "do not share", "never share",
        "2fa", "two-factor", "two factor", "mfa",
    )

    // Regex: a standalone 4-8 digit number is almost always an OTP
    private val OTP_CODE_PATTERN = Regex("""(?<!\d)\d{4,8}(?!\d)""")

    // ── Transactional keywords ─────────────────────────────────────────────
    private val TRANSACTIONAL_KEYWORDS = setOf(
        // Banking
        "debited", "credited", "account balance", "avail bal", "available balance",
        "a/c", "acct", "account no", "account number", "txn", "transaction",
        "upi", "neft", "imps", "rtgs", "net banking", "mobile banking",
        "bank", "atm", "debit card", "credit card",
        // Payments
        "paid", "payment", "payment received", "payment failed", "payment due",
        "amount", "inr", "usd", "rs.", "₹", "$", "emi", "loan",
        "invoice", "receipt", "bill", "recharge", "wallet", "paytm",
        "gpay", "phonepe", "bhim", "razorpay",
        // Deliveries and orders
        "delivered", "out for delivery", "shipped", "dispatched", "order",
        "tracking", "track your", "consignment", "package", "courier",
        "delivery", "pickup", "collect",
        // Alerts
        "alert", "reminder", "due date", "overdue", "statement",
    )

    // ── Promotional keywords ───────────────────────────────────────────────
    private val PROMOTIONAL_KEYWORDS = setOf(
        "offer", "discount", "sale", "% off", "flat off", "cashback",
        "coupon", "promo", "promocode", "deal", "free", "hurry",
        "limited time", "expires today", "last chance", "exclusive",
        "congratulations", "congrats", "winner", "won", "prize",
        "subscribe", "unsubscribe", "newsletter", "opt out", "opt-out",
        "click here", "tap here", "visit", "shop now", "buy now",
        "special offer", "festival sale", "mega sale", "clearance",
        "upgrade", "premium", "upgrade now", "refer", "earn",
        "reward", "points", "loyalty", "member",
        // Common sender patterns (uppercase senders)
        "dear customer", "dear user", "hello user",
    )

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Categorize a single conversation.
     *
     * Priority order:
     *  1. If the conversation has a named contact → PERSONAL
     *  2. OTP check (highest signal, must win over transactional)
     *  3. Transactional check
     *  4. Promotional check
     *  5. Default → PERSONAL (unknown sender treated as personal)
     */
    fun categorize(conversation: Conversation): SmsCategory {
        // 1 — Named contact → always PERSONAL
        val hasNamedContact = conversation.recipients.any { recipient ->
            recipient.contact?.name?.isNotBlank() == true
        }
        if (hasNamedContact) return SmsCategory.PERSONAL

        // Get the last message body for keyword scanning
        val body = conversation.lastMessage?.body?.lowercase() ?: return SmsCategory.PERSONAL

        // 2 — OTP check (keyword OR standalone numeric code)
        if (isOtp(body)) return SmsCategory.OTP

        // 3 — Transactional
        if (isTransactional(body)) return SmsCategory.TRANSACTIONAL

        // 4 — Promotional
        if (isPromotional(body)) return SmsCategory.PROMOTIONAL

        // 5 — Default: treat unknown senders as personal
        return SmsCategory.PERSONAL
    }

    /**
     * Categorize a batch of conversations efficiently.
     * Returns a Map from [SmsCategory] to the list of conversations in that category.
     */
    fun categorizeAll(
        conversations: List<Conversation>
    ): Map<SmsCategory, List<Conversation>> =
        conversations.groupBy { categorize(it) }

    /**
     * Count conversations per category. Useful for badge counts.
     */
    fun countByCategory(conversations: List<Conversation>): Map<SmsCategory, Int> =
        categorizeAll(conversations).mapValues { (_, list) -> list.size }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun isOtp(body: String): Boolean {
        // Keyword match
        if (OTP_KEYWORDS.any { body.contains(it) }) return true
        // Standalone 4-8 digit numeric code present
        if (OTP_CODE_PATTERN.containsMatchIn(body)) {
            // Extra guard: body must also contain a hint word to avoid
            // flagging pure transactional amounts (e.g. "₹5000")
            val otpHints = listOf("code", "otp", "password", "pin", "verify", "expires",
                "valid", "use", "enter", "digit")
            if (otpHints.any { body.contains(it) }) return true
        }
        return false
    }

    private fun isTransactional(body: String): Boolean =
        TRANSACTIONAL_KEYWORDS.any { body.contains(it) }

    private fun isPromotional(body: String): Boolean =
        PROMOTIONAL_KEYWORDS.any { body.contains(it) }
}
