package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvatarAccessTest {
    @Test
    fun supporterAvatarsAreOnlyPublishedWithAccess() {
        val standard = AvatarCatalogItem(id = "standard", storagePath = "standard.png")
        val supporter = AvatarCatalogItem(
            id = "supporter-gold",
            storagePath = "gold.png",
            localImageUrl = "file:///cache/supporter-gold.png",
            memberOnly = true,
        )

        assertEquals(
            builtInAvatarCatalog + listOf(standard),
            availableAvatarCatalog(listOf(standard), listOf(supporter), hasMemberAccess = false),
        )
        assertEquals(
            builtInAvatarCatalog + listOf(standard, supporter),
            availableAvatarCatalog(listOf(standard), listOf(supporter), hasMemberAccess = true),
        )
    }

    @Test
    fun bundledAvatarsRemainSelectableWhenRemoteCatalogIsEmpty() {
        val available = availableAvatarCatalog(emptyList(), emptyList(), hasMemberAccess = false)
        assertEquals(8, available.size)
        assertEquals(available.map { it.id }.distinct(), available.map { it.id })
        assertEquals(true, isBuiltInAvatar(available.first().id))
        assertNull(avatarImageUrl(available.first()))
    }

    @Test
    fun supporterAvatarUsesAuthenticatedLocalAsset() {
        val supporter = AvatarCatalogItem(
            id = "supporter-gold",
            storagePath = "private/gold.png",
            localImageUrl = "file:///cache/supporter-gold.png",
            memberOnly = true,
        )

        assertEquals("file:///cache/supporter-gold.png", avatarImageUrl(supporter))
        assertNull(avatarImageUrl(supporter.copy(localImageUrl = null)))
    }
}
