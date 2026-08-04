package com.kotlinworkshop.test

import kmpworkshop.common.advertiseExposedFile
import kmpworkshop.common.makeFileDownloadable
import kmpworkshop.server.CoroutinePuzzleErrorMessages
import org.junit.jupiter.api.Test

class CoroutinePuzzleGuidanceSnapshotTest {
    @Test
    fun `incorrect sum guidance`() {
        CoroutinePuzzleErrorMessages.incorrectSum(listOf(12, 30), 41).assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/incorrect_sum.txt",
        )
    }

    @Test
    fun `concurrent sum guidance`() {
        CoroutinePuzzleErrorMessages.sumCallsMustBeConcurrent().assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/concurrent_sum.txt",
        )
    }

    @Test
    fun `wrong oldest age guidance`() {
        CoroutinePuzzleErrorMessages.wrongOldestAge(43, 47).assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/wrong_oldest_age.txt",
        )
    }

    @Test
    fun `concurrent user queries guidance`() {
        CoroutinePuzzleErrorMessages.userQueriesMustBeConcurrent().assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/concurrent_user_queries.txt",
        )
    }

    @Test
    fun `unknown user guidance`() {
        CoroutinePuzzleErrorMessages.unknownUser(1234).assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/unknown_user.txt",
        )
    }

    @Test
    fun `wrong flow value guidance`() {
        CoroutinePuzzleErrorMessages.wrongFlowValue(7, 8).assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/wrong_flow_value.txt",
        )
    }

    @Test
    fun `concurrent exception calls guidance`() {
        CoroutinePuzzleErrorMessages.exceptionCallsMustBeConcurrent().assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/concurrent_exception_calls.txt",
        )
    }

    @Test
    fun `wrong reported exception guidance`() {
        CoroutinePuzzleErrorMessages.wrongReportedException("token refresh failed", "wrapper failed").assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/wrong_reported_exception.txt",
        )
    }

    @Test
    fun `missing reported exception message guidance`() {
        CoroutinePuzzleErrorMessages.wrongReportedException("token refresh failed", null).assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/missing_reported_exception_message.txt",
        )
    }

    @Test
    fun `legacy cancellation completion guidance`() {
        CoroutinePuzzleErrorMessages.cancellationMustFinishFirst().assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/legacy_cancellation_completion.txt",
        )
    }

    @Test
    fun `weak WiFi guidance`() {
        CoroutinePuzzleErrorMessages.weakWifiExposureStarted().assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/weak_wifi.txt",
        )
    }

    @Test
    fun `network restart guidance`() {
        CoroutinePuzzleErrorMessages.networkRestartStartedTooEarly(
            listOf(makeFileDownloadable, advertiseExposedFile),
        ).assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/network_restart.txt",
        )
    }

    @Test
    fun `wrong file guidance`() {
        CoroutinePuzzleErrorMessages.wrongFile("advertise", "the replacement file").assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/wrong_file.txt",
        )
    }

    @Test
    fun `wrong endpoint argument guidance`() {
        CoroutinePuzzleErrorMessages.wrongEndpointArgument("file-2", "file-1").assertMatchesSnapshot(
            "snapshots/CoroutinePuzzleGuidanceSnapshotTest/wrong_endpoint_argument.txt",
        )
    }

}
