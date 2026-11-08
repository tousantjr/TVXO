package com.nuvio.tv.domain.model

private val supporterThemes = linkedMapOf(
    AppTheme.GOLD to CosmeticEntitlement.GOLD_THEME,
    AppTheme.JADE to CosmeticEntitlement.JADE_THEME,
    AppTheme.ROSE_GOLD to CosmeticEntitlement.ROSE_GOLD_THEME,
    AppTheme.ARCTIC_BLUE to CosmeticEntitlement.ARCTIC_BLUE_THEME,
    AppTheme.GRAPHITE to CosmeticEntitlement.GRAPHITE_THEME
)

// TVXO: TVXO Pink first (the default), then White and the rest.
private val standardThemes = listOf(AppTheme.TVXO_PINK, AppTheme.WHITE) + AppTheme.entries.filterNot {
    it == AppTheme.TVXO_PINK || it == AppTheme.WHITE || it == AppTheme.CUSTOM || it in supporterThemes
}

fun availableAppThemes(entitlements: CosmeticEntitlements): List<AppTheme> {
    val unlockedSupporterThemes = supporterThemes
        .filterValues(entitlements::includes)
        .keys
        .toList()
    return unlockedSupporterThemes + AppTheme.CUSTOM + standardThemes
}

fun resolveAppTheme(
    selectedTheme: AppTheme?,
    entitlements: CosmeticEntitlements
): AppTheme {
    if (selectedTheme == null) {
        return supporterThemes
            .filterValues(entitlements::includes)
            .keys
            .firstOrNull()
            ?: AppTheme.TVXO_PINK
    }
    return if (selectedTheme in availableAppThemes(entitlements)) {
        selectedTheme
    } else {
        AppTheme.TVXO_PINK
    }
}

fun resolveCustomThemeColors(colors: CustomThemeColors, memberTier: MemberTier?): CustomThemeColors =
    if (memberTier == null) CustomThemeColors.solid(colors.second) else colors
