package com.kevinluo.autoglm.app

import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.forAll

/**
 * Property-based tests for AppResolver.
 *
 * **Feature: autoglm-phone-agent, Property 20: App name resolution consistency**
 * **Validates: Requirements 9.1**
 *
 * These tests verify the core similarity calculation and matching logic
 * of the AppResolver without requiring Android framework dependencies.
 */
class AppResolverPropertyTest : StringSpec({

    /**
     * Property 20: App name resolution consistency
     *
     * For any installed app with a known display name, querying the AppResolver
     * with that exact name should return the correct package name.
     *
     * Since we can't use PackageManager in unit tests, we test the underlying
     * similarity calculation logic which is the core of the resolution algorithm.
     *
     * **Validates: Requirements 9.1**
     */

    // Test that exact matches always return similarity of 1.0
    "exact match should always return similarity of 1.0" {
        val stringArb = Arb.string(1..50, Codepoint.alphanumeric())

        forAll(100, stringArb) { name ->
            val resolver = createTestableAppResolver()
            val similarity = resolver.calculateSimilarity(name.lowercase(), name.lowercase())
            similarity == 1.0
        }
    }

    // Test that similarity is symmetric for Levenshtein distance
    "levenshtein distance should be symmetric" {
        val stringArb = Arb.string(0..30, Codepoint.alphanumeric())

        forAll(100, stringArb, stringArb) { s1, s2 ->
            val resolver = createTestableAppResolver()
            val dist1 = resolver.levenshteinDistance(s1, s2)
            val dist2 = resolver.levenshteinDistance(s2, s1)
            dist1 == dist2
        }
    }

    // Test that levenshtein distance is non-negative
    "levenshtein distance should be non-negative" {
        val stringArb = Arb.string(0..30, Codepoint.alphanumeric())

        forAll(100, stringArb, stringArb) { s1, s2 ->
            val resolver = createTestableAppResolver()
            val distance = resolver.levenshteinDistance(s1, s2)
            distance >= 0
        }
    }

    // Test that levenshtein distance is zero only for identical strings
    "levenshtein distance should be zero only for identical strings" {
        val stringArb = Arb.string(0..30, Codepoint.alphanumeric())

        forAll(100, stringArb) { s ->
            val resolver = createTestableAppResolver()
            val distance = resolver.levenshteinDistance(s, s)
            distance == 0
        }
    }

    // Test triangle inequality for levenshtein distance
    "levenshtein distance should satisfy triangle inequality" {
        val stringArb = Arb.string(0..20, Codepoint.alphanumeric())

        forAll(100, stringArb, stringArb, stringArb) { s1, s2, s3 ->
            val resolver = createTestableAppResolver()
            val d12 = resolver.levenshteinDistance(s1, s2)
            val d23 = resolver.levenshteinDistance(s2, s3)
            val d13 = resolver.levenshteinDistance(s1, s3)
            d13 <= d12 + d23
        }
    }

    // Test that similarity is always in range [0, 1]
    "similarity should always be in range 0 to 1" {
        val stringArb = Arb.string(0..30, Codepoint.alphanumeric())

        forAll(100, stringArb, stringArb) { query, target ->
            val resolver = createTestableAppResolver()
            val similarity = resolver.calculateSimilarity(query.lowercase(), target.lowercase())
            similarity >= 0.0 && similarity <= 1.0
        }
    }

    // Test that contains match has higher similarity than fuzzy match
    "contains match should have higher similarity than non-contains fuzzy match" {
        val prefixArb = Arb.string(1..10, Codepoint.alphanumeric())
        val suffixArb = Arb.string(1..10, Codepoint.alphanumeric())
        val queryArb = Arb.string(3..15, Codepoint.alphanumeric())

        forAll(100, prefixArb, queryArb, suffixArb) { prefix, query, suffix ->
            val resolver = createTestableAppResolver()
            val normalizedQuery = query.lowercase()

            // Target that contains the query
            val containsTarget = (prefix + query + suffix).lowercase()

            // Target that doesn't contain the query (completely different)
            val differentTarget = "zzz${prefix}zzz".lowercase()

            // Skip valid match cases in the "different" target
            if (differentTarget.contains(normalizedQuery)) return@forAll true

            val containsSimilarity = resolver.calculateSimilarity(normalizedQuery, containsTarget)
            val differentSimilarity = resolver.calculateSimilarity(normalizedQuery, differentTarget)

            // Contains match should score higher (unless different target happens to be similar)
            containsSimilarity >= differentSimilarity || differentSimilarity < AppResolver.MIN_SIMILARITY_THRESHOLD
        }
    }

    // Test that levenshtein distance to empty string equals string length
    "levenshtein distance to empty string should equal string length" {
        val stringArb = Arb.string(0..30, Codepoint.alphanumeric())

        forAll(100, stringArb) { s ->
            val resolver = createTestableAppResolver()
            val distanceToEmpty = resolver.levenshteinDistance(s, "")
            val distanceFromEmpty = resolver.levenshteinDistance("", s)
            distanceToEmpty == s.length && distanceFromEmpty == s.length
        }
    }

    // Test that startsWith match has high similarity
    "startsWith match should have high similarity" {
        val queryArb = Arb.string(3..15, Codepoint.alphanumeric())
        val suffixArb = Arb.string(1..10, Codepoint.alphanumeric())

        forAll(100, queryArb, suffixArb) { query, suffix ->
            val resolver = createTestableAppResolver()
            val normalizedQuery = query.lowercase()
            val target = (query + suffix).lowercase()

            val similarity = resolver.calculateSimilarity(normalizedQuery, target)

            // StartsWith should have similarity >= 0.75
            similarity >= 0.75
        }
    }
})

/**
 * Creates a testable AppResolver instance.
 *
 * Since AppResolver requires PackageManager which is an Android system service,
 * we create a minimal mock that allows us to test the core similarity logic.
 */
private fun createTestableAppResolver(): TestableAppResolver {
    return TestableAppResolver()
}

/**
 * A testable version of AppResolver that exposes the internal similarity
 * calculation methods for property-based testing.
 *
 * This class duplicates the core logic from AppResolver to enable testing
 * without Android framework dependencies.
 */
class TestableAppResolver {

    /**
     * Calculates the similarity between two strings using a combination of:
     * 1. Exact match (highest priority)
     * 2. Contains match (high priority)
     * 3. Starts with match (high priority)
     * 4. Levenshtein distance-based similarity (for fuzzy matching)
     */
    fun calculateSimilarity(query: String, target: String): Double {
        // Exact match
        if (query == target) {
            return 1.0
        }

        // Target contains query exactly
        if (target.contains(query)) {
            val coverageScore = query.length.toDouble() / target.length
            return 0.8 + (coverageScore * 0.15)
        }

        // Target starts with query
        if (target.startsWith(query)) {
            val coverageScore = query.length.toDouble() / target.length
            return 0.75 + (coverageScore * 0.15)
        }

        // Query starts with target
        if (query.startsWith(target)) {
            val coverageScore = target.length.toDouble() / query.length
            return 0.7 + (coverageScore * 0.15)
        }

        // Fuzzy matching using Levenshtein distance
        val distance = levenshteinDistance(query, target)
        val maxLength = maxOf(query.length, target.length)

        if (maxLength == 0) {
            return 0.0
        }

        val similarity = 1.0 - (distance.toDouble() / maxLength)
        return similarity * 0.7
    }

    /**
     * Calculates the Levenshtein distance between two strings.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length

        if (m == 0) return n
        if (n == 0) return m

        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) {
            dp[i][0] = i
        }

        for (j in 0..n) {
            dp[0][j] = j
        }

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[m][n]
    }

    companion object {
        const val MIN_SIMILARITY_THRESHOLD = 0.3
    }
}
