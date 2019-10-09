package com.adaptris.security.pgp;

import com.adaptris.annotation.AdapterComponent;
import com.adaptris.annotation.AdvancedConfig;
import com.adaptris.annotation.ComponentProfile;
import com.adaptris.annotation.DisplayOrder;
import com.adaptris.annotation.InputFieldDefault;
import com.adaptris.core.AdaptrisMessage;
import com.adaptris.core.ServiceException;
import com.adaptris.core.common.MetadataDataOutputParameter;
import com.adaptris.core.common.MetadataStreamInputParameter;
import com.adaptris.core.common.PayloadStreamInputParameter;
import com.adaptris.interlok.config.DataInputParameter;
import com.adaptris.interlok.config.DataOutputParameter;
import com.thoughtworks.xstream.annotations.XStreamAlias;
import org.apache.commons.lang.BooleanUtils;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.BCPGOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SignatureException;
import java.util.Iterator;

/**
 * This service provides a way to sign messages via GPG/PGP. It requires
 * a private key, the passphrase to unlock the key, and a message to
 * sign. Optionally it will ASCII armor encode the signature (default)
 * and create a detached signature (default).
 *
 * @author aanderson
 * @config pgp-sign
 */
@XStreamAlias("pgp-sign")
@AdapterComponent
@ComponentProfile(summary = "Sign data using a PGP/GPG private key", tag = "pgp,gpg,sign,signature,private key")
@DisplayOrder(order = { "privateKey", "passphrase", "clearText", "signature" })
public class PGPSignService extends PGPService
{
	private static transient Logger log = LoggerFactory.getLogger(PGPSignService.class);

	/* TODO digest could be an advanced option */
	private static final int DIGEST = HashAlgorithmTags.SHA256;

	@NotNull
	@Valid
	private DataInputParameter privateKey = new MetadataStreamInputParameter();

	@NotNull
	@Valid
	private DataInputParameter passphrase = new MetadataStreamInputParameter();

	@NotNull
	@Valid
	private DataInputParameter clearText = new PayloadStreamInputParameter();

	@Valid
	@AdvancedConfig
	@InputFieldDefault(value = "true")
	private Boolean armorEncoding = true;

	@Valid
	@AdvancedConfig
	@InputFieldDefault(value = "true")
	private Boolean detachedSignature = true;

	@NotNull
	@Valid
	private DataOutputParameter signature = new MetadataDataOutputParameter();

	/**
	 * {@inheritDoc}.
	 */
	@Override
	public void doService(AdaptrisMessage message) throws ServiceException
	{
		try
		{
			InputStream key = extractStream(message, privateKey, "Could not read private key");
			String password = extractString(message, passphrase, "Could not read passphrase");
			InputStream data = extractStream(message, clearText, "Could not read clear text message to sign");
			ByteArrayOutputStream sig = new ByteArrayOutputStream();
			if (detachedSignature)
			{
				sign(data, key, password.toCharArray(), DIGEST, armorEncoding, sig);
			}
			else
			{
				sign(data, key, password.toCharArray(), DIGEST, sig);
			}
			insertStream(message, signature, sig);
		}
		catch (Exception e)
		{
			log.error("An error occurred during PGP signing", e);
			throw new ServiceException(e);
		}
	}

	/**
	 * Set the private key for decryption.
	 *
	 * @param privateKey The private key.
	 */
	public void setPrivateKey(DataInputParameter privateKey)
	{
		this.privateKey = privateKey;
	}

	/**
	 * Get the private key for decryption.
	 *
	 * @return The private key.
	 */
	public DataInputParameter getPrivateKey()
	{
		return privateKey;
	}

	/**
	 * Set the passphrase to unlock the private key.
	 *
	 * @param passphrase The passphrase.
	 */
	public void setPassphrase(DataInputParameter passphrase)
	{
		this.passphrase = passphrase;
	}

	/**
	 * Get the passphrase to unlock the private key.
	 *
	 * @return The passphrase.
	 */
	public DataInputParameter getPassphrase()
	{
		return passphrase;
	}

	/**
	 * Set the data to sign.
	 *
	 * @param clearText The data to sign.
	 */
	public void setClearText(DataInputParameter clearText)
	{
		this.clearText = clearText;
	}

	/**
	 * Get the data to sign.
	 *
	 * @return The data to sign.
	 */
	public DataInputParameter getClearText()
	{
		return clearText;
	}

	/**
	 * Set whether the signature output should be ASCII armor encoded.
	 *
	 * @param armorEncoding Whether the signature should be armor encoded.
	 */
	public void setArmorEncoding(Boolean armorEncoding)
	{
		this.armorEncoding = BooleanUtils.toBooleanDefaultIfNull(armorEncoding, true);
	}

	/**
	 * Get whether the signature output should be ASCII armor encoded.
	 *
	 * @return Whether the signature should be armor encoded.
	 */
	public Boolean getArmorEncoding()
	{
		return armorEncoding;
	}

	/**
	 * Set whether the signature should be detached from the message.
	 *
	 * @param detachedSignature Whether the signature should be detached.
	 */
	public void setDetachedSignature(Boolean detachedSignature)
	{
		this.detachedSignature = BooleanUtils.toBooleanDefaultIfNull(detachedSignature, true);
	}

	/**
	 * Get whether the signature should be detached from the message.
	 *
	 * @return Whether the signature should be detached.
	 */
	public Boolean getDetachedSignature()
	{
		return detachedSignature;
	}

	/**
	 * Set the signature.
	 *
	 * @param signature The signature.
	 */
	public void setSignature(DataOutputParameter signature)
	{
		this.signature = signature;
	}

	/**
	 * Get the signature.
	 *
	 * @return The signature.
	 */
	public DataOutputParameter getSignature()
	{
		return signature;
	}

	/**
	 * Create an inline, clear signature (always ASCII armor encoded).
	 *
	 * @param in     The message to sign.
	 * @param key    The private key.
	 * @param passwd The key passphrase.
	 * @param digest The digest algorithm to use.
	 * @param out
	 * @param out    The generated signataure.
	 * @throws PGPException       Thrown if there's a problem with the key/passphrase.
	 * @throws IOException        Thrown if there's an IO issue.
	 * @throws SignatureException Thrown if there's a problem creating the signature.
	 */
	private static void sign(InputStream in, InputStream key, char[] passwd, int digest, OutputStream out) throws PGPException, IOException, SignatureException
	{
		PGPSecretKey pgpSec = readSecretKey(key);
		PGPPrivateKey pgpPrivKey = pgpSec.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder().setProvider("BC").build(passwd));
		PGPSignatureGenerator sGen = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(pgpSec.getPublicKey().getAlgorithm(), digest).setProvider("BC"));
		PGPSignatureSubpacketGenerator spGen = new PGPSignatureSubpacketGenerator();
		sGen.init(PGPSignature.CANONICAL_TEXT_DOCUMENT, pgpPrivKey);
		Iterator it = pgpSec.getPublicKey().getUserIDs();
		if (it.hasNext())
		{
			spGen.setSignerUserID(false, (String)it.next());
			sGen.setHashedSubpackets(spGen.generate());
		}
		InputStream fIn = new BufferedInputStream(in);
		ArmoredOutputStream aOut = new ArmoredOutputStream(out);
		aOut.beginClearText(digest);
		//
		// note the last \n/\r/\r\n in the file is ignored
		//
		ByteArrayOutputStream lineOut = new ByteArrayOutputStream();
		int lookAhead = readInputLine(lineOut, fIn);
		processLine(aOut, sGen, lineOut.toByteArray());
		if (lookAhead != -1)
		{
			do
			{
				lookAhead = readInputLine(lineOut, lookAhead, fIn);
				sGen.update((byte)'\r');
				sGen.update((byte)'\n');
				processLine(aOut, sGen, lineOut.toByteArray());
			}
			while (lookAhead != -1);
		}
		fIn.close();
		aOut.endClearText();
		BCPGOutputStream bOut = new BCPGOutputStream(aOut);
		sGen.generate().encode(bOut);
		aOut.close();
	}

	/**
	 * Create a detached signature, optionally ASCII armor encoded.
	 *
	 * @param in     The message to sign.
	 * @param key    The private key.
	 * @param passwd The key passphrase.
	 * @param digest The digest algorithm to use.
	 * @param armor  Whether to armor encode the signature.
	 * @param out    The generated signataure.
	 * @throws PGPException Thrown if there's a problem with the key/passphrase.
	 * @throws IOException  Thrown if there's an IO issue.
	 */
	private static void sign(InputStream in, InputStream key, char[] passwd, int digest, boolean armor, OutputStream out) throws PGPException, IOException
	{
		if (armor)
		{
			out = new ArmoredOutputStream(out);
		}
		PGPSecretKey pgpSec = readSecretKey(key);
		PGPPrivateKey pgpPrivKey = pgpSec.extractPrivateKey(new JcePBESecretKeyDecryptorBuilder().setProvider("BC").build(passwd));
		PGPSignatureGenerator sGen = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(pgpSec.getPublicKey().getAlgorithm(), digest).setProvider("BC"));
		sGen.init(PGPSignature.BINARY_DOCUMENT, pgpPrivKey);
		BCPGOutputStream bOut = new BCPGOutputStream(out);
		int ch;
		while ((ch = in.read()) >= 0)
		{
			sGen.update((byte)ch);
		}
		sGen.generate().encode(bOut);
		if (armor)
		{
			out.close();
		}
	}

	/**
	 * A simple routine that opens a key ring file and loads the first available key
	 * suitable for signature generation.
	 *
	 * @param input stream to read the secret key ring collection from.
	 * @return a secret key.
	 * @throws IOException  on a problem with using the input stream.
	 * @throws PGPException if there is an issue parsing the input stream.
	 */
	private static PGPSecretKey readSecretKey(InputStream input) throws IOException, PGPException
	{
		PGPSecretKeyRingCollection pgpSec = new PGPSecretKeyRingCollection(getDecoderStream(input), new JcaKeyFingerprintCalculator());
		//
		// we just loop through the collection till we find a key suitable for encryption, in the real
		// world you would probably want to be a bit smarter about this.
		//
		Iterator keyRingIter = pgpSec.getKeyRings();
		while (keyRingIter.hasNext())
		{
			PGPSecretKeyRing keyRing = (PGPSecretKeyRing)keyRingIter.next();
			Iterator keyIter = keyRing.getSecretKeys();
			while (keyIter.hasNext())
			{
				PGPSecretKey key = (PGPSecretKey)keyIter.next();
				if (key.isSigningKey())
				{
					return key;
				}
			}
		}
		throw new IllegalArgumentException("Can't find signing key in key ring");
	}
}
