package dev.devilmusic.proof

/** Media3 session IPC can omit localConfiguration. Resolve only exact retained content grants. */
fun grantedDocumentIdentity(mediaId: String, configuredUri: String?, grants: Set<String>): String? =
    listOfNotNull(configuredUri, mediaId).firstOrNull { it.startsWith("content://") && it in grants }
