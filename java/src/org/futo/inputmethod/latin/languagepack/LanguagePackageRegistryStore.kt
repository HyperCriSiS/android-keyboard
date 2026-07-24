package org.futo.inputmethod.latin.languagepack

fun LanguagePackageStore.buildRegistry(): LanguagePackageRegistry {
    return LanguagePackageRegistry.fromInstalledPackages(listInstalled())
}
