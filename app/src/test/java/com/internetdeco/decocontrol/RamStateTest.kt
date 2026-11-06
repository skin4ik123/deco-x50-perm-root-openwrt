package com.internetdeco.decocontrol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RamStateTest {
    private val id = "1234567890abcdef1234567890abcdef"
    private fun operation(phase: String) = JSONObject().put("phase", phase).put("operation_id", id).put("resolved", false)

    @Test fun rebootAndTransportUncertaintyNeverClaimOpenWrtBooted() {
        for (phase in listOf("env_write_requested", "env_write_unconfirmed", "armed_verified", "reboot_requested", "reboot_unconfirmed")) {
            val state = RamState(operation(phase).put("started", true))
            assertTrue(state.pending)
            assertFalse(state.openWrtVerified)
            assertFalse(state.canStart)
            if (phase.startsWith("env_") || phase == "armed_verified") assertFalse(state.started)
        }
        assertTrue(ramOperationTitle("ram_start", operation("reboot_requested")).contains("not yet verified"))
    }
    @Test fun freshPreparationNeedsItsOwnConfirmationAndCannotBeReplayed() {
        assertTrue(RamState(operation("prepared").put("can_start", true)).canStart)
        assertFalse(RamState(operation("reboot_requested").put("can_start", true)).canStart)
        assertFalse(RamState(null).canStart)
    }
    @Test fun previousRamReceiptDoesNotClaimCurrentOpenWrtAfterStockReturn() {
        val receipt = operation("openwrt_verified").put("openwrt", JSONObject().put("verified", true))
        assertTrue(RamState(receipt).openWrtVerified)
        receipt.put("phase", "stock_verified").put("resolved", true)
        receipt.put("started", true).put("can_prepare", true)
        val returned = RamState(receipt)
        assertFalse(returned.pending)
        assertFalse(returned.openWrtVerified)
        assertFalse(returned.started)
        assertTrue(returned.canPrepare)
        assertEquals("Stock boot policy restored", returned.title)
    }
    @Test fun absentNativeJournalUsesRealNullFieldsWithoutCreatingAnOperation() {
        val state = RamState(JSONObject().put("phase", "none").put("resolved", true).put("operation_id", JSONObject.NULL).put("backup_folder", JSONObject.NULL))
        assertEquals("", state.operationId)
        assertNull(state.backupFolder)
        assertFalse(state.pending)
        assertEquals("Try OpenWrt in RAM", state.title)
    }
    @Test fun tftpEndpointRejectsNetworkBroadcastRouterAndInjection() {
        for (value in listOf("192.168.1.0", "192.168.1.1", "192.168.1.255", "192.168.2.2", "192.168.01.2", "192.168.1.2;reboot", "10.0.0.2")) assertFalse(value, validTftpServer(value))
        for (value in listOf("192.168.1.2", "192.168.1.199", "192.168.1.254")) assertTrue(value, validTftpServer(value))
    }
    @Test fun stockAndRamSessionsHaveDifferentKeysOnTheSameAddress() {
        val endpoint = RamEndpoint("192.168.1.1", id)
        assertNotEquals("192.168.1.1", endpoint.terminalKey)
        assertNotEquals("pin:192.168.1.1:22", "${endpoint.pinScope}:192.168.1.1:22")
        assertEquals(endpoint, RamEndpoint.fromTerminalKey(endpoint.terminalKey))
        assertNotEquals(endpoint, RamEndpoint("10.20.30.40", id))
        for (bad in listOf("ram:10.0.0.2:bad", "ram:8.8.8.8:$id", "192.168.1.1", "ram:10.0.0.2:$id:extra")) {
            assertTrue(runCatching { RamEndpoint.fromTerminalKey(bad) }.isFailure)
        }
    }
    @Test fun imageIsReadyOnlyAfterSuccessfulVerification() {
        assertFalse(RamImageState(downloading = true, received = 13_056_916).verified)
        assertFalse(RamImageState(error = "Checksum mismatch").verified)
        assertTrue(RamImageState(path = "/private/firmware/image.itb").verified)
    }
    @Test fun ramConnectionStatusNeverMarksAStockProfileConnectedAtTheSameIp() {
        val endpoint = RamEndpoint("192.168.1.1", id)
        val ram = ConnectionState(RamEndpoint.HOST, phase = ConnectionPhase.CONNECTED, profileKey = endpoint.terminalKey)
        val state = ScreenState(nodes = listOf(Node("192.168.1.1", "Stock")), connections = mapOf(ram.key to ram))
        assertFalse(state.connection("192.168.1.1").connected)
        assertTrue(state.connection(endpoint.terminalKey).connected)
    }
}
