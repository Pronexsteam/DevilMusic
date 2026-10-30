// SPDX-License-Identifier: GPL-3.0-or-later
package dev.devilmusic.androidproof
import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OnlineLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        retryDelay(info.exception,info.errorCount) {super.getRetryDelayMsFor(info)}
    override fun getFallbackSelectionFor(options: LoadErrorHandlingPolicy.FallbackOptions,info: LoadErrorHandlingPolicy.LoadErrorInfo): LoadErrorHandlingPolicy.FallbackSelection? =
        if (terminal(info.exception)) null else super.getFallbackSelectionFor(options,info)
    companion object {
        fun retryDelay(error:Throwable,errorCount:Int,localDefault:()->Long):Long {
            val failure=find(error) ?: return localDefault()
            // Two retries per consecutive load-error sequence; Media3 resets after progress.
            // Resume the same retained lease at Media3's current byte position. No source refresh,
            // alternate client, fallback format or retry of protection/cancellation failures.
            return if(OnlineTransportFailure.recoverable(failure) && errorCount in 1..2)errorCount*500L else C.TIME_UNSET
        }
        fun terminal(error: Throwable): Boolean {
            return find(error)!=null
        }
        private fun find(error:Throwable):OnlineSourceException? {
            var current: Throwable?=error
            repeat(8) { if(current is OnlineSourceException)return current as OnlineSourceException;current=current?.cause }
            return null
        }
    }
}
