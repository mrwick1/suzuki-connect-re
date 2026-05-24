package dev.mrwick.gixxerbridge.data

/**
 * Immutable set of package names allowed to mirror notifications to the bike.
 *
 * All mutators return a new [AppAllowlist] — the underlying set is never mutated.
 */
data class AppAllowlist(val packages: Set<String>) {
    /** True when [pkg] is in the allowlist. */
    fun contains(pkg: String): Boolean = pkg in packages

    /** Return a new allowlist with [pkg] added. */
    fun with(pkg: String): AppAllowlist = AppAllowlist(packages + pkg)

    /** Return a new allowlist with [pkg] removed. */
    fun without(pkg: String): AppAllowlist = AppAllowlist(packages - pkg)
}
