package de.schildbach.wallet.network;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.DevNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.quorums.LLMQParameters;
import org.junit.Test;

import static org.junit.Assert.*;

public class SakuraDevNetParamsTest {
    // A synthetic public address for constructor tests, not Sakura's signing key.
    private String testAddress() {
        return Address.fromPubKeyHash(TestNet3Params.get(), new byte[20]).toBase58();
    }

    @Test
    public void usesSakuraIdentityAndRegistersParameters() {
        SakuraDevNetParams params = SakuraDevNetParams.create(testAddress(), 20001);
        assertEquals("devnet-sakura", params.getDevNetName());
        assertEquals(20001, params.getPort());
        assertEquals(70240, params.getProtocolVersionNum(NetworkParameters.ProtocolVersion.CURRENT));
        assertSame(params, DevNetParams.get("sakura"));
        assertEquals(13, params.getDefaultMasternodeList().length);
        assertArrayEquals(params.getDefaultMasternodeList(), params.getDefaultHPMasternodeList());
    }

    @Test
    public void peersAreDefensivelyCopiedAndPortCanBeOverridden() {
        SakuraDevNetParams params = SakuraDevNetParams.create(testAddress(), 20002);
        params.getDefaultMasternodeList()[0] = "127.0.0.1";
        assertEquals("68.67.122.86", params.getDefaultMasternodeList()[0]);
        assertEquals(20002, params.getPort());
    }

    @Test
    public void selectsSakuraRotatedAndPlatformQuorumsWithoutChangingIntervals() {
        SakuraDevNetParams params = SakuraDevNetParams.create(testAddress(), 20001);
        assertEquals(LLMQParameters.LLMQType.LLMQ_DEVNET_DIP0024, params.getLlmqDIP0024InstantSend());
        assertEquals(LLMQParameters.LLMQType.LLMQ_DEVNET_PLATFORM, params.getLlmqPlatform());
        assertTrue(params.getLlmqs().get(params.getLlmqDIP0024InstantSend()).useRotation());
        assertEquals(48, params.getLlmqs().get(params.getLlmqDIP0024InstantSend()).getDkgInterval());
        assertFalse(params.getLlmqs().get(params.getLlmqPlatform()).useRotation());
        assertEquals(24, params.getLlmqs().get(params.getLlmqPlatform()).getDkgInterval());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingSporkAddress() {
        SakuraDevNetParams.create("", 20001);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidPort() {
        SakuraDevNetParams.create(testAddress(), 65536);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidSporkAddress() {
        SakuraDevNetParams.create("not-an-address", 20001);
    }
}
