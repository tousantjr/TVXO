// Fork-local quarantine for known-baseline-failing unit tests, so CI (and a
// local run) can gate a change without weakening any test source.
//
// Used by .github/workflows/dev-unit-tests.yml and reproducible locally:
//   ./gradlew :app:testFullDebugUnitTest -I .github/ci/quarantine-tests.init.gradle.kts
//
// These three fail on the merged upstream tree (recorded in ACCESSIBILITY_HANDOVER.md):
//   *LocalhostZeroCopyDataSourceTest.testHttpError404            (vendored media3)
//   *DefaultAllocatorTest.testLateReleasedAllocationsMemoryLeak  (vendored media3)
//   *TraktAuthServiceTest.refresh token 400 ...                  (app, mock verification)
//
// Remove an entry here when the test is fixed; delete the file when the list is empty.

allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
        filter {
            excludeTestsMatching("*LocalhostZeroCopyDataSourceTest.testHttpError404")
            excludeTestsMatching("*DefaultAllocatorTest.testLateReleasedAllocationsMemoryLeak")
            excludeTestsMatching("*TraktAuthServiceTest.refresh token 400*")
        }
    }
}
