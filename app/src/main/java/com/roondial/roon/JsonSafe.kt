package com.roondial.roon

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading JSON the way Android actually behaves.
 *
 * `JSONObject.optString` does not do what the JVM's reference implementation
 * does. On Android, a field whose value is JSON null comes back as the four
 * characters "null" rather than an empty string, so a null `image_key` becomes
 * a non-empty key and the app cheerfully asks the Core for artwork called
 * "null"; a null subtitle renders as the word null under a track.
 *
 * The unit tests here cannot catch it: they run on the JVM against the
 * reference org.json, where the bug does not exist. So the rule is enforced by
 * JsonSafeTest, which reads the source and fails if optString comes back.
 */
fun JSONObject.str(key: String, fallback: String = ""): String =
    if (isNull(key)) fallback else optString(key, fallback)

/** As [str], for a field that is absent, empty or JSON null. */
fun JSONObject.strOrNull(key: String): String? = str(key).takeIf { it.isNotEmpty() }

fun JSONArray.str(index: Int, fallback: String = ""): String =
    if (isNull(index)) fallback else optString(index, fallback)
