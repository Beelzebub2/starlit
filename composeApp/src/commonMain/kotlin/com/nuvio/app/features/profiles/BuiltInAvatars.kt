package com.nuvio.app.features.profiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private const val BUILTIN_AVATAR_PREFIX = "starlit:avatar:"

/**
 * Bundled, offline-safe choices for Starlit's own account server, which does not
 * host a remote cosmetic catalog. IDs are stable and synchronize like remote IDs.
 * The character names are proper nouns rather than untranslated interface copy.
 */
internal val builtInAvatarCatalog = listOf(
    AvatarCatalogItem(id = "starlit:avatar:nova", displayName = "Nova", bgColor = "#6D28D9", category = "builtin", sortOrder = 0),
    AvatarCatalogItem(id = "starlit:avatar:mira", displayName = "Mira", bgColor = "#0D9488", category = "builtin", sortOrder = 1),
    AvatarCatalogItem(id = "starlit:avatar:echo", displayName = "Echo", bgColor = "#2563EB", category = "builtin", sortOrder = 2),
    AvatarCatalogItem(id = "starlit:avatar:blaze", displayName = "Blaze", bgColor = "#B45309", category = "builtin", sortOrder = 3),
    AvatarCatalogItem(id = "starlit:avatar:orbit", displayName = "Orbit", bgColor = "#BE185D", category = "builtin", sortOrder = 4),
    AvatarCatalogItem(id = "starlit:avatar:lyra", displayName = "Lyra", bgColor = "#0E7490", category = "builtin", sortOrder = 5),
    AvatarCatalogItem(id = "starlit:avatar:pixel", displayName = "Pixel", bgColor = "#9D174D", category = "builtin", sortOrder = 6),
    AvatarCatalogItem(id = "starlit:avatar:fern", displayName = "Fern", bgColor = "#15803D", category = "builtin", sortOrder = 7),
)

internal fun isBuiltInAvatar(avatarId: String?): Boolean =
    avatarId != null && avatarId.startsWith(BUILTIN_AVATAR_PREFIX) && builtInAvatarCatalog.any { it.id == avatarId }

@Composable
internal fun BuiltInAvatarIcon(
    avatarId: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val image = when (avatarId.removePrefix(BUILTIN_AVATAR_PREFIX)) {
        "nova" -> Icons.Rounded.Star
        "mira" -> Icons.Rounded.Face
        "echo" -> Icons.Rounded.MusicNote
        "blaze" -> Icons.Rounded.AutoAwesome
        "orbit" -> Icons.Rounded.Favorite
        "lyra" -> Icons.Rounded.Pets
        "pixel" -> Icons.Rounded.SportsEsports
        else -> Icons.Rounded.Person
    }
    Icon(imageVector = image, contentDescription = contentDescription, tint = Color.White, modifier = modifier)
}
