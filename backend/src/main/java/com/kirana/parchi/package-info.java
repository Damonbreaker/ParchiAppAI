/**
 * Parchi photo scan (one-time, never stored). ParchiController -> ParchiService -> ParchiReader
 * (LocalOcrParchiReader / MockParchiReader) and ParchiLineParser (lines -> items). The photo only
 * lives in memory for the call.
 */
package com.kirana.parchi;
