/*
 * Copyright 2014-2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.payments;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.hash.Hashing;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.Moshi;

import de.schildbach.wallet.Constants;
import static de.schildbach.wallet.Constants.HEX;

import de.schildbach.wallet_test.BuildConfig;
import de.schildbach.wallet_test.R;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;

import de.schildbach.wallet_test.BuildConfig;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

/**
 * @author Andreas Schildbach
 */
public final class RequestWalletBalanceTask {
	private final Handler backgroundHandler;
	private final Handler callbackHandler;
	private final ResultCallback resultCallback;

	private static final Logger log = LoggerFactory.getLogger(RequestWalletBalanceTask.class);

	public interface ResultCallback {
		void onResult(Set<UTXO> utxos);

		void onFail(int messageResId, Object... messageArgs);
	}

	public RequestWalletBalanceTask(final Handler backgroundHandler, final ResultCallback resultCallback) {
		this.backgroundHandler = backgroundHandler;
		this.callbackHandler = new Handler(Looper.myLooper());
		this.resultCallback = resultCallback;
	}

	public static class JsonRpcRequest {
		public final int id;
		public final String method;
		public final String[] params;

		private static transient int idCounter = 0;

		public JsonRpcRequest(final String method, final String[] params) {
			this.id = idCounter++;
			this.method = method;
			this.params = params;
		}
	}

	public static class JsonRpcResponse {
		public int id;
		public Utxo[] result;

		public static class Utxo {
			public String tx_hash;
			public int tx_pos;
			public long value;
			public int height;
		}
	}

	public void requestWalletBalance(final AssetManager assets, final Address address) {
		backgroundHandler.post(new Runnable() {
			@Override
			public void run() {
				org.bitcoinj.core.Context.propagate(Constants.CONTEXT);

				try {
					final List<ElectrumServer> servers = loadElectrumServers(
							assets.open(Constants.Files.ELECTRUM_SERVERS_FILENAME));
					final ElectrumServer server = servers.get(new Random().nextInt(servers.size()));
					log.info("trying to request wallet balance from {}: {}", server.socketAddress, address);
					final Socket socket;
					if (server.type == ElectrumServer.Type.TLS) {
						final SocketFactory sf = sslTrustAllCertificates();
						socket = sf.createSocket(server.socketAddress.getHostName(), server.socketAddress.getPort());
						final SSLSession sslSession = ((SSLSocket) socket).getSession();
						final Certificate certificate = sslSession.getPeerCertificates()[0];
						final String certificateFingerprint = sslCertificateFingerprint(certificate);
						if (server.certificateFingerprint == null) {
							// signed by CA
							if (!HttpsURLConnection.getDefaultHostnameVerifier()
									.verify(server.socketAddress.getHostName(), sslSession))
								throw new SSLHandshakeException("Expected " + server.socketAddress.getHostName()
										+ ", got " + sslSession.getPeerPrincipal());
						} else {
							// self-signed
							if (!certificateFingerprint.equals(server.certificateFingerprint))
								throw new SSLHandshakeException("Expected " + server.certificateFingerprint + ", got "
										+ certificateFingerprint);
						}
					} else if (server.type == ElectrumServer.Type.TCP) {
						socket = new Socket();
						socket.connect(server.socketAddress, 5000);
					} else {
						throw new IllegalStateException("Cannot handle: " + server.type);
					}
					final BufferedSink sink = Okio.buffer(Okio.sink(socket));
					sink.timeout().timeout(5000, TimeUnit.MILLISECONDS);
					final BufferedSource source = Okio.buffer(Okio.source(socket));
					source.timeout().timeout(5000, TimeUnit.MILLISECONDS);
					final Moshi moshi = new Moshi.Builder().build();
					final JsonAdapter<JsonRpcRequest> requestAdapter = moshi.adapter(JsonRpcRequest.class);
					final JsonRpcRequest request = new JsonRpcRequest("blockchain.address.listunspent",
							new String[] { address.toString() });
					requestAdapter.toJson(sink, request);
					sink.writeUtf8("\n").flush();
					final JsonAdapter<JsonRpcResponse> responseAdapter = moshi.adapter(JsonRpcResponse.class);
					final JsonRpcResponse response = responseAdapter.fromJson(source);
					if (response.id == request.id) {
						final Set<UTXO> utxos = new HashSet<>();
						for (final JsonRpcResponse.Utxo responseUtxo : response.result) {
							if (response.result == null)
								throw new JsonDataException("empty response");
							final Sha256Hash utxoHash = Sha256Hash.wrap(responseUtxo.tx_hash);
							final int utxoIndex = responseUtxo.tx_pos;
							final Coin utxoValue = Coin.valueOf(responseUtxo.value);
							final Script script = ScriptBuilder.createOutputScript(address);
							final UTXO utxo = new UTXO(utxoHash, utxoIndex, utxoValue, responseUtxo.height, false,
									script);
							utxos.add(utxo);
						}

						log.info("fetched {} unspent outputs from {}", response.result.length, server.socketAddress);
						onResult(utxos);
					} else {
						log.info("id mismatch response:{} vs request:{}", response.id, request.id);
						if(!requestWalletBalanceFromBlockExplorers(address))
							onFail(R.string.error_parse, server.socketAddress.toString());
					}
				} catch (final JsonDataException x) {
					log.info("problem parsing json", x);
					if(!requestWalletBalanceFromBlockExplorers(address))
						onFail(R.string.error_parse, x.getMessage());
				} catch (final IOException x) {
					log.info("problem querying unspent outputs", x);
					if(!requestWalletBalanceFromBlockExplorers(address))
						onFail(R.string.error_io, x.getMessage());
				}
			}
		});
	}

	protected void onResult(final Set<UTXO> utxos) {
		callbackHandler.post(new Runnable() {
			@Override
			public void run() {
				resultCallback.onResult(utxos);
			}
		});
	}

	protected void onFail(final int messageResId, final Object... messageArgs) {
		callbackHandler.post(new Runnable() {
			@Override
			public void run() {
				resultCallback.onFail(messageResId, messageArgs);
			}
		});
	}

	public static class ElectrumServer {
		public enum Type {
			TCP, TLS
		}

		public final InetSocketAddress socketAddress;
		public final Type type;
		public final String certificateFingerprint;

		public ElectrumServer(final String type, final String host, final String port,
				final String certificateFingerprint) {
			this.type = Type.valueOf(type.toUpperCase());
			if (port != null)
				this.socketAddress = InetSocketAddress.createUnresolved(host, Integer.parseInt(port));
			else if ("tcp".equalsIgnoreCase(type))
				this.socketAddress = InetSocketAddress.createUnresolved(host,
						Constants.ELECTRUM_SERVER_DEFAULT_PORT_TCP);
			else if ("tls".equalsIgnoreCase(type))
				this.socketAddress = InetSocketAddress.createUnresolved(host,
						Constants.ELECTRUM_SERVER_DEFAULT_PORT_TLS);
			else
				throw new IllegalStateException("Cannot handle: " + type);
			this.certificateFingerprint = certificateFingerprint;
		}
	}

	private static List<ElectrumServer> loadElectrumServers(final InputStream is) throws IOException {
		final Splitter splitter = Splitter.on(':').trimResults();
		final List<ElectrumServer> servers = new LinkedList<>();
		BufferedReader reader = null;
		String line = null;
		try {
			reader = new BufferedReader(new InputStreamReader(is, Charsets.UTF_8));
			while (true) {
				line = reader.readLine();
				if (line == null)
					break;
				line = line.trim();
				if (line.length() == 0 || line.charAt(0) == '#')
					continue;

				final Iterator<String> i = splitter.split(line).iterator();
				final String type = i.next();
				final String host = i.next();
				final String port = i.hasNext() ? Strings.emptyToNull(i.next()) : null;
				final String fingerprint = i.hasNext() ? Strings.emptyToNull(i.next()) : null;
				servers.add(new ElectrumServer(type, host, port, fingerprint));
			}
		} catch (final Exception x) {
			throw new RuntimeException("Error while parsing: '" + line + "'", x);
		} finally {
			if (reader != null)
				reader.close();
			is.close();
		}
		return servers;
	}

	private SSLSocketFactory sslTrustAllCertificates() {
		try {
			final SSLContext context = SSLContext.getInstance("SSL");
			context.init(null, new TrustManager[] { TRUST_ALL_CERTIFICATES }, null);
			final SSLSocketFactory socketFactory = context.getSocketFactory();
			return socketFactory;
		} catch (final Exception x) {
			throw new RuntimeException(x);
		}
	}

	private static final X509TrustManager TRUST_ALL_CERTIFICATES = new X509TrustManager() {
		@Override
		public void checkClientTrusted(final X509Certificate[] chain, final String authType)
				throws CertificateException {
		}

		@Override
		public void checkServerTrusted(final X509Certificate[] chain, final String authType)
				throws CertificateException {
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	};

	private String sslCertificateFingerprint(final Certificate certificate) {
		try {
			return Hashing.sha256().newHasher().putBytes(certificate.getEncoded()).hash().toString();
		} catch (final Exception x) {
			throw new RuntimeException(x);
		}
	}

	enum UnspentAPI {
		CryptoId,
		ABE,
		Insight
	}

	boolean requestWalletBalanceFromBlockExplorers(Address address) {
		Stack<Pair<String, UnspentAPI>> blockExplorers = new Stack<>();

		if (BuildConfig.FLAVOR.equals("prod")) {
			blockExplorers.push(new Pair<>("https://chainz.cryptoid.info/dash/api.dws?q=unspent", UnspentAPI.CryptoId));
			blockExplorers.push(new Pair<>("https://insight.dash.org/insight-api/addr/", UnspentAPI.Insight));
		} else if (BuildConfig.FLAVOR.equals("_testNet3") || BuildConfig.FLAVOR.equals("staging")) {
			blockExplorers.push(new Pair<>("https://insight.testnet.networks.dash.org:3002/insight-api/addr/", UnspentAPI.Insight));
		} else if (BuildConfig.FLAVOR.equals("schnapps")) {
			blockExplorers.push(new Pair<>(String.format("http://insight.%s.networks.dash.org:3002/insight-api/addr/", Constants.NETWORK_PARAMETERS.getDevNetName().substring("devnet-".length())), UnspentAPI.Insight));
		}

		while (!blockExplorers.empty()) {
			Pair<String, UnspentAPI> explorer = blockExplorers.pop();
			final Set<UTXO> utxos = requestWalletBalanceFromBlockExplorer(explorer.first, explorer.second, address);
			if (utxos != null) {
				onResult(utxos);
				return true;
			}
		}
		onFail(R.string.error_io, "cannot connect to any block explorer for unspent outputs");
		return false;
	}


	Set<UTXO> requestWalletBalanceFromBlockExplorer(String blockExplorerUrl, UnspentAPI unspentAPI, Address address) {

		final StringBuilder url = new StringBuilder(blockExplorerUrl);
		if(unspentAPI == UnspentAPI.CryptoId)
		{
			url.append("&key=d47da926b82e");    //Cryptoid API key
			url.append("&active=").append(address.toString());
		} else if(unspentAPI == UnspentAPI.ABE) {
			url.append(address.toString());
		} else if(unspentAPI == UnspentAPI.Insight) {
			url.append(address.toString());
			url.append("/utxo");
		}
		log.debug("trying to request wallet balance from {}", url);

		final Request request = new Request.Builder()
				.url(url.toString())
				.header("User-Agent", Constants.USER_AGENT)
				.build();

		final Call call = org.dash.wallet.common.util.Constants.INSTANCE.getHTTP_CLIENT().newCall(request);

		try
		{
			final Response response = call.execute();

			if(response.isSuccessful())
			{
				final String content = response.body().string();
				JSONArray outputs = null;

				if(unspentAPI == UnspentAPI.CryptoId)
				{
					JSONObject head = new JSONObject(content);
					outputs = head.getJSONArray("unspent_outputs");
				} else if(unspentAPI == UnspentAPI.ABE) {
					if(content.startsWith("No free outputs to spend"))
						return null;
					JSONObject head = new JSONObject(content);
					outputs = head.getJSONArray("unspent_outputs");
				} else if(unspentAPI == UnspentAPI.Insight) {
					outputs = new JSONArray(content);
				}

				final Set<UTXO> utxos = new HashSet<>();

				for (int i = 0; i < outputs.length(); i++)
				{
					final JSONObject jsonOutput = outputs.getJSONObject(i);

					Sha256Hash hash = null;
					int index = 0;
					byte[] scryptBytes = null;
					Coin value = null;
					int blockNumber = 0;

					if(unspentAPI == UnspentAPI.ABE) {
						hash = Sha256Hash.wrap(jsonOutput.getString("tx_hash"));
						index = jsonOutput.getInt("tx_output_n");
						scryptBytes = HEX.decode(jsonOutput.getString("script"));
						value = Coin.valueOf(jsonOutput.getLong("value"));
						blockNumber = jsonOutput.getInt("block_number");
					}
					else if(unspentAPI == UnspentAPI.CryptoId) {
						hash = Sha256Hash.wrap(jsonOutput.getString("tx_hash"));
						index = jsonOutput.getInt("tx_ouput_n"); //yes, output is spelled as "ouput"
						scryptBytes = ScriptBuilder.createOutputScript(address).getProgram();
						value = Coin.valueOf(jsonOutput.getLong("value"));
					} else if(unspentAPI == UnspentAPI.Insight) {
						if(jsonOutput.has("height")) //unconfirmed
							blockNumber = jsonOutput.getInt("height");
						hash = Sha256Hash.wrap(jsonOutput.getString("txid"));
						index = jsonOutput.getInt("vout");
						scryptBytes = HEX.decode(jsonOutput.getString("scriptPubKey"));
						value = Coin.valueOf(jsonOutput.getLong("satoshis"));
					}
					utxos.add(new UTXO(hash, index, value, blockNumber, false, new Script(scryptBytes), address.toString()));
				}

				log.info("fetched unspent outputs from {}", url);
				return utxos;
			}
			else
			{
				log.info("got http error '{}: {}' from {}", response.code(), response.message(), url);
				return null;
			}
		}
		catch (final JSONException x)
		{
			log.info("problem parsing json from " + url, x);
			return null;
		}
		catch (final IOException x)
		{
			log.info("problem querying unspent outputs from " + url, x);
			return null;
		}
	}
}
