package com.fit5120tp10.nextcross.client.historicalcrash

fun interface HistoricalCrashContextClient {
    fun lookup(request: HistoricalCrashContextRequest): HistoricalCrashContextResult
}
