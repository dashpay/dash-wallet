package de.schildbach.wallet.ui;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.UnsafeByteArrayOutputStream;
import org.bitcoinj.core.Utils;
import org.bitcoinj.protocols.payments.PaymentProtocolException;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptOpCodes;
import org.dash.android.lightpayprot.Output;
import org.dash.android.lightpayprot.data.SimplifiedPayment;
import org.dash.android.lightpayprot.data.SimplifiedPaymentRequest;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.data.PaymentIntent;

import static org.bitcoinj.core.Utils.HEX;
import static org.bitcoinj.script.ScriptOpCodes.OP_INVALIDOPCODE;

public class SimplifiedPaymentRequestUtil {

    /**
     * Converts `OP_DUP OP_HASH160 20 0x49fc92b29aba29d8ecbace53c1298aa3d1f47803 OP_EQUALVERIFY OP_CHECKSIG`
     * to `DUP HASH160 PUSHDATA(20)[49fc92b29aba29d8ecbace53c1298aa3d1f47803] EQUALVERIFY CHECKSIG`
     *
     * @param string
     * @return
     * @throws IOException
     */
    public static Script parseScriptString(String string) throws IOException {
        String[] words = string.split("[ \\t\\n]");

        UnsafeByteArrayOutputStream out = new UnsafeByteArrayOutputStream();

        for (String w : words) {
            if (w.equals(""))
                continue;
            if (w.matches("^-?[0-9]*$")) {
                // Number
                long val = Long.parseLong(w);
                out.write(Utils.reverseBytes(Utils.encodeMPI(BigInteger.valueOf(val), false)));
//                if (val >= -1 && val <= 16)
//                    out.write(Script.encodeToOpN((int) val));
//                else
//                    Script.writeBytes(out, Utils.reverseBytes(Utils.encodeMPI(BigInteger.valueOf(val), false)));
            } else if (w.matches("^0x[0-9a-fA-F]*$")) {
                // Raw hex data, inserted NOT pushed onto stack:
                out.write(HEX.decode(w.substring(2).toLowerCase()));
            } else if (w.length() >= 2 && w.startsWith("'") && w.endsWith("'")) {
                // Single-quoted string, pushed as data. NOTE: this is poor-man's
                // parsing, spaces/tabs/newlines in single-quoted strings won't work.
                Script.writeBytes(out, w.substring(1, w.length() - 1).getBytes(StandardCharsets.UTF_8));
            } else if (ScriptOpCodes.getOpCode(w) != OP_INVALIDOPCODE) {
                // opcode, e.g. OP_ADD or OP_1:
                out.write(ScriptOpCodes.getOpCode(w));
            } else if (w.startsWith("OP_") && ScriptOpCodes.getOpCode(w.substring(3)) != OP_INVALIDOPCODE) {
                // opcode, e.g. OP_ADD or OP_1:
                out.write(ScriptOpCodes.getOpCode(w.substring(3)));
            } else {
                throw new RuntimeException("Invalid Data");
            }
        }

        return new Script(out.toByteArray());
    }

    public static PaymentIntent convert(SimplifiedPaymentRequest data) throws PaymentProtocolException.InvalidOutputs {
        String rawScript = null;
        try {
            List<PaymentIntent.Output> outputs = new ArrayList<>();
            List<Output> dataOutputs = data.getOutputs();
            for (Output out : dataOutputs) {
                Coin amount = Coin.valueOf((int) out.getAmount());
                rawScript = out.getScript();
                Script script = SimplifiedPaymentRequestUtil.parseScriptString(rawScript);
                Address address = Address.fromBase58(Constants.NETWORK_PARAMETERS, out.getAddress());
                outputs.add(new PaymentIntent.Output(amount, script, address, out.getDescription()));
            }
            PaymentIntent.Output[] outputsArr = outputs.toArray(new PaymentIntent.Output[0]);
            return new PaymentIntent(PaymentIntent.Standard.BIP270, data.getPayeeName(), data.getPayeeVerifiedBy(),
                    outputsArr, data.getMemo(), data.getPaymentUrl(), null, data.getMerchantData(), null);

        } catch (IOException ex) {
            throw new PaymentProtocolException.InvalidOutputs("unparseable script in output: " + rawScript);
        }
    }

    public static SimplifiedPayment createSimplifiedPayment(String merchantData, String transaction, String refundTo, String memo) {

        return new SimplifiedPayment("merchantData", transaction, refundTo, memo);
    }
}
