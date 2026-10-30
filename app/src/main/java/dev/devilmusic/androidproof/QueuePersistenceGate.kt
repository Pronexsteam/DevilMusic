package dev.devilmusic.androidproof
/** A fast open/close must not replace a queue before its asynchronous restoration finishes. */
internal class QueuePersistenceGate {
    private var loaded=false
    private var edited=false
    fun loaded(success:Boolean=true){loaded=success}
    fun edited(){edited=true}
    fun canSave()=loaded || edited
    fun canRestore()=!edited
}
