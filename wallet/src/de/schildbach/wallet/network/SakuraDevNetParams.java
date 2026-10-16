package de.schildbach.wallet.network;

import org.bitcoinj.core.Address;
import org.bitcoinj.params.DevNetParams;

/** Wallet-local configuration; consensus defaults are inherited from DashJ. */
public final class SakuraDevNetParams extends DevNetParams {
    private static final String[] PEERS = {
        "68.67.122.86", "68.67.122.87", "68.67.122.240", "68.67.122.241",
        "68.67.122.88", "68.67.122.89", "68.67.122.90", "68.67.122.91",
        "68.67.122.92", "68.67.122.93", "68.67.122.94", "68.67.122.95",
        "68.67.122.232"
    };

    private SakuraDevNetParams(String sporkAddress, int port) {
        super("sakura", sporkAddress, port, PEERS.clone(), true, 70240);
        Address.fromBase58(this, sporkAddress);
    }

    public static synchronized SakuraDevNetParams create(String sporkAddress, int port) {
        if (sporkAddress == null || sporkAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("Set sakuraSporkAddress to Sakura's public spork address");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("sakuraCorePort must be between 1 and 65535");
        }
        SakuraDevNetParams params = new SakuraDevNetParams(sporkAddress, port);
        DevNetParams.add(params);
        return params;
    }

    @Override
    public String[] getDefaultMasternodeList() {
        return PEERS.clone();
    }

    @Override
    public String[] getDefaultHPMasternodeList() {
        return PEERS.clone();
    }
}
