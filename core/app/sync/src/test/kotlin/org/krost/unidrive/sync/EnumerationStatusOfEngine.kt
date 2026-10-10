package org.krost.unidrive.sync

// What a client learns about the enumeration: the status of the engine's one RemoteEnumeration
// (:app:engine-core), the instance the mount front-end reports through `MountEngine.enumerationStatus`.
// Read here without the mount front-end, so this module's tests need no :app:hydration.
internal fun SyncEngine.enumerationStatus(): EnumerationStatus = mountWiring.enumeration.status()
