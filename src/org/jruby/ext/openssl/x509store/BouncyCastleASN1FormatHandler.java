/***** BEGIN LICENSE BLOCK *****
 * Version: CPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Common Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/cpl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2006 Ola Bini <ola@ologix.com>
 * Copyright (C) 2007 William N Dortch <bill.dortch@gmail.com>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the CPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the CPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby.ext.openssl.x509store;

import java.io.IOException;
import java.io.Writer;
import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.Reader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import java.math.BigInteger;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.cert.X509CRL;
import java.security.cert.CertificateEncodingException;
import java.security.interfaces.DSAParams;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.DSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.InvalidParameterSpecException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.spec.DHParameterSpec;

import org.jruby.ext.openssl.OpenSSLReal;
import org.jruby.ext.openssl.PKCS10CertificationRequestExt;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1OutputStream;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERInteger;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.x509.DSAParameter;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.RSAPrivateKeyStructure;
import org.bouncycastle.asn1.x509.RSAPublicKeyStructure;
import org.bouncycastle.crypto.PBEParametersGenerator;
import org.bouncycastle.crypto.generators.OpenSSLPBEParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.encoders.Hex;

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.spec.DSAPrivateKeySpec;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.StringTokenizer;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.asn1.pkcs.PKCS12PBEParams;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.jruby.ext.openssl.impl.ASN1Registry;
import org.jruby.ext.openssl.impl.ASN1FormatHandler;
import org.jruby.util.ByteList;

/**
 * Helper class to read and write PEM files correctly.
 *
 * @author <a href="mailto:ola.bini@ki.se">Ola Bini</a>
 */
public class BouncyCastleASN1FormatHandler implements ASN1FormatHandler {
    private static final Pattern DH_PARAM_PATTERN = Pattern.compile(
            "(-----BEGIN DH PARAMETERS-----)(.*)(-----END DH PARAMETERS-----)",
            Pattern.MULTILINE);
    private static final int DH_PARAM_GROUP = 2; // the group above containing encoded params

    private static SecureRandom random;
    static {
        try {
            random = SecureRandom.getInstance("SHA1PRNG");
        } catch (Exception e) {
            random = null;
        }
    }
    
    public BouncyCastleASN1FormatHandler() {
        // nothing to do.
    }
    
    private BufferedReader makeBuffered(Reader in) {
        if(in instanceof BufferedReader) {
            return (BufferedReader)in;
        }
        return new BufferedReader(in);
    }

    private BufferedWriter makeBuffered(Writer out) {
        if(out instanceof BufferedWriter) {
            return (BufferedWriter)out;
        }
        return new BufferedWriter(out);
    }

    /**
     * c: PEM_X509_INFO_read_bio
     */
    @Override
    public Object readPEM(Reader in,char[] f) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String  line;
        while ((line = _in.readLine()) != null) {
            if(line.indexOf(BEF_G+PEM_STRING_PUBLIC) != -1) {
                try {
                    return readPublicKey(_in,BEF_E+PEM_STRING_PUBLIC);
                } catch (Exception e) {
                    throw new IOException("problem creating public key: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_DSA) != -1) {
                try {
                    return readKeyPair(_in,f, "DSA", BEF_E+PEM_STRING_DSA);
                } catch (Exception e) {
                    throw new IOException("problem creating DSA private key: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_RSA_PUBLIC) != -1) {
                try {
                    return readPublicKey(_in,BEF_E+PEM_STRING_RSA_PUBLIC);
                } catch (Exception e) {
                    throw new IOException("problem creating RSA public key: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_X509_OLD) != -1) {
                try {
                    return readAuxCertificate(_in,BEF_E+PEM_STRING_X509_OLD);
                } catch (Exception e) {
                    throw new IOException("problem creating X509 Aux certificate: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_X509) != -1) {
                try {
                    return readAuxCertificate(_in,BEF_E+PEM_STRING_X509);
                } catch (Exception e) {
                    throw new IOException("problem creating X509 Aux certificate: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_X509_TRUSTED) != -1) {
                try {
                    return readAuxCertificate(_in,BEF_E+PEM_STRING_X509_TRUSTED);
                } catch (Exception e) {
                    throw new IOException("problem creating X509 Aux certificate: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_X509_CRL) != -1) {
                try {
                    return readCRL(_in,BEF_E+PEM_STRING_X509_CRL);
                } catch (Exception e) {
                    throw new IOException("problem creating X509 CRL: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_X509_REQ) != -1) {
                try {
                    return readCertificateRequest(_in,BEF_E+PEM_STRING_X509_REQ);
                } catch (Exception e) {
                    throw new IOException("problem creating X509 REQ: " + e.toString());
                }
            }
        }
        return null; 
    }

    @Override
    public byte[] readPEMToDER(Reader in) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String line;
        while ((line = _in.readLine()) != null) {
            if (line.indexOf(BEF_G + PEM_STRING_PUBLIC) != -1) {
                try {
                    return readBytes(_in, BEF_E + PEM_STRING_PUBLIC);
                } catch (Exception e) {
                    throw new IOException("problem reading PEM public key: " + e.toString());
                }
            } else if (line.indexOf(BEF_G + PEM_STRING_DSA) != -1) {
                try {
                    return readBytes(_in, BEF_E + PEM_STRING_DSA);
                } catch (Exception e) {
                    throw new IOException("problem reading PEM DSA private key: " + e.toString());
                }
            } else if (line.indexOf(BEF_G + PEM_STRING_RSA_PUBLIC) != -1) {
                try {
                    return readBytes(_in, BEF_E + PEM_STRING_RSA_PUBLIC);
                } catch (Exception e) {
                    throw new IOException("problem reading PEM RSA public key: " + e.toString());
                }
            } else if (line.indexOf(BEF_G + PEM_STRING_X509_OLD) != -1) {
                try {
                    return readBytes(_in, BEF_E + PEM_STRING_X509_OLD);
                } catch (Exception e) {
                    throw new IOException("problem reading PEM X509 Aux certificate: " + e.toString());
                }
            } else if (line.indexOf(BEF_G + PEM_STRING_X509) != -1) {
                try {
                    return readBytes(_in, BEF_E + PEM_STRING_X509);
                } catch (Exception e) {
                    throw new IOException("problem reading PEM X509 Aux certificate: " + e.toString());
                }
            } else if (line.indexOf(BEF_G + PEM_STRING_X509_TRUSTED) != -1) {
                try {
                    return readBytes(_in, BEF_E + PEM_STRING_X509_TRUSTED);
                } catch (Exception e) {
                    throw new IOException("problem reading PEM X509 Aux certificate: " + e.toString());
                }
            } else if (line.indexOf(BEF_G + PEM_STRING_X509_CRL) != -1) {
                try {
                    return readBytes(_in, BEF_E + PEM_STRING_X509_CRL);
                } catch (Exception e) {
                    throw new IOException("problem reading PEM X509 CRL: " + e.toString());
                }
            } else if (line.indexOf(BEF_G + PEM_STRING_X509_REQ) != -1) {
                try {
                    return readBytes(_in, BEF_E + PEM_STRING_X509_REQ);
                } catch (Exception e) {
                    throw new IOException("problem reading PEM X509 REQ: " + e.toString());
                }
            }
        }
        return null;
    }

    /**
     * c: PEM_read_PrivateKey + PEM_read_bio_PrivateKey
     * CAUTION: KeyPair#getPublic() may be null.
     */
    @Override
    public KeyPair readPrivateKey(Reader in, char[] password) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String line;
        while ((line = _in.readLine()) != null) {
            if (line.indexOf(BEF_G + PEM_STRING_RSA) != -1) {
                try {
                    return readKeyPair(_in, password, "RSA", BEF_E + PEM_STRING_RSA);
                } catch (Exception e) {
                    throw new IOException("problem creating RSA private key: " + e.toString());
                }
            } else if (line.indexOf(BEF_G + PEM_STRING_DSA) != -1) {
                try {
                    return readKeyPair(_in, password, "DSA", BEF_E + PEM_STRING_DSA);
                } catch (Exception e) {
                    throw new IOException("problem creating DSA private key: " + e.toString());
                }
            } else if (line.indexOf(BEF_G + PEM_STRING_ECPRIVATEKEY) != -1) {
                throw new IOException("EC private key not supported");
            } else if (line.indexOf(BEF_G + PEM_STRING_PKCS8INF) != -1) {
                try {
                    byte[] bytes = readBytes(_in, BEF_E + PEM_STRING_PKCS8INF);
                    ByteArrayInputStream bIn = new ByteArrayInputStream(bytes);
                    ASN1InputStream aIn = new ASN1InputStream(bIn);
                    PrivateKeyInfo info = new PrivateKeyInfo((ASN1Sequence) aIn.readObject());
                    String type = getPrivateKeyTypeFromObjectId(info.getAlgorithmId().getObjectId());
                    return readPrivateKeySequence(info.getPrivateKey().getDEREncoded(), type);
                } catch (Exception e) {
                    throw new IOException("problem creating private key: " + e.toString());
                }
            } else if (line.indexOf(BEF_G + PEM_STRING_PKCS8) != -1) {
                try {
                    byte[] bytes = readBytes(_in, BEF_E + PEM_STRING_PKCS8);
                    ByteArrayInputStream bIn = new ByteArrayInputStream(bytes);
                    ASN1InputStream aIn = new ASN1InputStream(bIn);
                    org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo eIn = new org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo((ASN1Sequence) aIn.readObject());
                    AlgorithmIdentifier algId = eIn.getEncryptionAlgorithm();
                    String algorithm = ASN1Registry.o2a(algId.getObjectId());
                    algorithm = (algorithm.split("-"))[0];
                    PKCS12PBEParams pbeParams = new PKCS12PBEParams((ASN1Sequence) algId.getParameters());
                    SecretKeyFactory fact = OpenSSLReal.getSecretKeyFactoryBC(algorithm); // need to use BC for PKCS12PBEParams.
                    PBEKeySpec pbeSpec = new PBEKeySpec(password);
                    SecretKey key = fact.generateSecret(pbeSpec);
                    PBEParameterSpec defParams = new PBEParameterSpec(pbeParams.getIV(), pbeParams.getIterations().intValue());
                    Cipher cipher = OpenSSLReal.getCipherBC(algorithm); // need to use BC for PBEParameterSpec.
                    cipher.init(Cipher.UNWRAP_MODE, key, defParams);
                    // wrappedKeyAlgorithm is unknown ("")
                    PrivateKey privKey = (PrivateKey) cipher.unwrap(eIn.getEncryptedData(), "", Cipher.PRIVATE_KEY);
                    return new KeyPair(null, privKey);
                } catch (Exception e) {
                    throw new IOException("problem creating private key: " + e.toString());
                }
            }
        }
        return null;
    }

    /*
     * c: PEM_read_bio_DSA_PUBKEY
     */
    @Override
    public DSAPublicKey readDSAPubKey(Reader in, char[] f) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String  line;
        while ((line = _in.readLine()) != null) {
            if(line.indexOf(BEF_G+PEM_STRING_DSA_PUBLIC) != -1) {
                try {
                    return (DSAPublicKey)readPublicKey(_in,"DSA",BEF_E+PEM_STRING_DSA_PUBLIC);
                } catch (Exception e) {
                    throw new IOException("problem creating DSA public key: " + e.toString());
                }
            }
        }
        return null;
    }

    /*
     * c: PEM_read_bio_DSAPublicKey
     */
    @Override
    public DSAPublicKey readDSAPublicKey(Reader in, char[] f) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String  line;
        while ((line = _in.readLine()) != null) {
            if(line.indexOf(BEF_G+PEM_STRING_PUBLIC) != -1) {
                try {
                    return (DSAPublicKey)readPublicKey(_in,"DSA",BEF_E+PEM_STRING_PUBLIC);
                } catch (Exception e) {
                    throw new IOException("problem creating DSA public key: " + e.toString());
                }
            }
        }
        return null; 
    }

    /*
     * c: PEM_read_bio_DSAPrivateKey
     */
    @Override
    public KeyPair readDSAPrivateKey(Reader in, char[] f) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String  line;
        while ((line = _in.readLine()) != null) {
            if(line.indexOf(BEF_G+PEM_STRING_DSA) != -1) {
                try {
                    return readKeyPair(_in,f, "DSA", BEF_E+PEM_STRING_DSA);
                } catch (Exception e) {
                    throw new IOException("problem creating DSA private key: " + e.toString());
                }
            }
        }
        return null; 
    }

    /**
     * reads an RSA public key encoded in an SubjectPublicKeyInfo RSA structure.
     * c: PEM_read_bio_RSA_PUBKEY
     */
    @Override
    public RSAPublicKey readRSAPubKey(Reader in, char[] f) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String  line;
        while ((line = _in.readLine()) != null) {
            if(line.indexOf(BEF_G+PEM_STRING_PUBLIC) != -1) {
                try {
                    return readRSAPublicKey(_in,BEF_E+PEM_STRING_PUBLIC);
                } catch (Exception e) {
                    throw new IOException("problem creating RSA public key: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_RSA_PUBLIC) != -1) {
                try {
                    return readRSAPublicKey(_in,BEF_E+PEM_STRING_RSA_PUBLIC);
                } catch (Exception e) {
                    throw new IOException("problem creating RSA public key: " + e.toString());
                }
            }
        }
        return null; 
    }

    /**
     * reads an RSA public key encoded in an PKCS#1 RSA structure.
     * c: PEM_read_bio_RSAPublicKey
     */
    @Override
    public RSAPublicKey readRSAPublicKey(Reader in, char[] f) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String  line;
        while ((line = _in.readLine()) != null) {
            if(line.indexOf(BEF_G+PEM_STRING_PUBLIC) != -1) {
                try {
                    return (RSAPublicKey)readPublicKey(_in,"RSA",BEF_E+PEM_STRING_PUBLIC);
                } catch (Exception e) {
                    throw new IOException("problem creating RSA public key: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_RSA_PUBLIC) != -1) {
                try {
                    return (RSAPublicKey)readPublicKey(_in,"RSA",BEF_E+PEM_STRING_RSA_PUBLIC);
                } catch (Exception e) {
                    throw new IOException("problem creating RSA public key: " + e.toString());
                }
            }
        }
        return null; 
    }

    /**
     * c: PEM_read_bio_RSAPrivateKey
     */
    @Override
    public KeyPair readRSAPrivateKey(Reader in, char[] f) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String  line;
        while ((line = _in.readLine()) != null) {
            if(line.indexOf(BEF_G+PEM_STRING_RSA) != -1) {
                try {
                    return readKeyPair(_in,f, "RSA", BEF_E+PEM_STRING_RSA);
                } catch (Exception e) {
                    throw new IOException("problem creating RSA private key: " + e.toString());
                }
            }
        }
        return null;
    }

    public X509AuxCertificate readX509Certificate(Reader in, char[] f) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String  line;
        while ((line = _in.readLine()) != null) {
            if(line.indexOf(BEF_G+PEM_STRING_X509_OLD) != -1) {
                try {
                    return new X509AuxCertificate(readCertificate(_in,BEF_E+PEM_STRING_X509_OLD));
                } catch (Exception e) {
                    throw new IOException("problem creating X509 certificate: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_X509) != -1) {
                try {
                    return new X509AuxCertificate(readCertificate(_in,BEF_E+PEM_STRING_X509));
                } catch (Exception e) {
                    throw new IOException("problem creating X509 certificate: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_X509_TRUSTED) != -1) {
                try {
                    return new X509AuxCertificate(readCertificate(_in,BEF_E+PEM_STRING_X509_TRUSTED));
                } catch (Exception e) {
                    throw new IOException("problem creating X509 certificate: " + e.toString());
                }
            }
        }
        return null;
    }

    public X509AuxCertificate readX509Aux(Reader in, char[] f) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String  line;
        while ((line = _in.readLine()) != null) {
            if(line.indexOf(BEF_G+PEM_STRING_X509_OLD) != -1) {
                try {
                    return readAuxCertificate(_in,BEF_E+PEM_STRING_X509_OLD);
                } catch (Exception e) {
                    throw new IOException("problem creating X509 Aux certificate: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_X509) != -1) {
                try {
                    return readAuxCertificate(_in,BEF_E+PEM_STRING_X509);
                } catch (Exception e) {
                    throw new IOException("problem creating X509 Aux certificate: " + e.toString());
                }
            } else if(line.indexOf(BEF_G+PEM_STRING_X509_TRUSTED) != -1) {
                try {
                    return readAuxCertificate(_in,BEF_E+PEM_STRING_X509_TRUSTED);
                } catch (Exception e) {
                    throw new IOException("problem creating X509 Aux certificate: " + e.toString());
                }
            }
        }
        return null;
    }
    @Override
    public X509CRL readX509CRL(Reader in, char[] f) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String  line;
        while ((line = _in.readLine()) != null) {
            if(line.indexOf(BEF_G+PEM_STRING_X509_CRL) != -1) {
                try {
                    return readCRL(_in,BEF_E+PEM_STRING_X509_CRL);
                } catch (Exception e) {
                    throw new IOException("problem creating X509 CRL: " + e.toString());
                }
            }
        }
        return null;
    }

    public PKCS10CertificationRequestExt readX509Request(Reader in, char[] f) throws IOException {
        BufferedReader _in = makeBuffered(in);
        String  line;
        while ((line = _in.readLine()) != null) {
            if(line.indexOf(BEF_G+PEM_STRING_X509_REQ) != -1) {
                try {
                    return readCertificateRequest(_in,BEF_E+PEM_STRING_X509_REQ);
                } catch (Exception e) {
                    throw new IOException("problem creating X509 REQ: " + e.toString());
                }
            }
        }
        return null;
    }

    @Override
    public DHParameterSpec readDHParameters(Reader _in)
    throws IOException, InvalidParameterSpecException {
        BufferedReader in = makeBuffered(_in);
        String line;
        StringBuilder buf = new StringBuilder();
        while ((line = in.readLine()) != null) {
            if (line.indexOf(BEF_G + PEM_STRING_DHPARAMS) >= 0) {
                do {
                    buf.append(line.trim());
                } while (line.indexOf(BEF_E + PEM_STRING_DHPARAMS) < 0 &&
                        (line = in.readLine()) != null);
                break;
            }
        }
        Matcher m = DH_PARAM_PATTERN.matcher(buf.toString());
        if (m.find()) {
            try {
                byte[] decoded = Base64.decode(m.group(DH_PARAM_GROUP));
                ASN1InputStream aIn = new ASN1InputStream(new ByteArrayInputStream(decoded));
                ASN1Sequence seq = (ASN1Sequence)aIn.readObject();
                BigInteger p = ((DERInteger)seq.getObjectAt(0)).getValue();
                BigInteger g = ((DERInteger)seq.getObjectAt(1)).getValue();
                return new DHParameterSpec(p, g);
            } catch (Exception e) {}
        }
        // probably not exactly the intended use of this exception, but
        // close enough for internal throw/catch
        throw new InvalidParameterSpecException("invalid " + PEM_STRING_DHPARAMS);
    }
    
    private byte[] getEncoded(java.security.Key key) {
        if (key != null) {
            return key.getEncoded();
        }
        return new byte[] { '0', 0 };
    }

    private byte[] getEncoded(ASN1Encodable obj) throws IOException {
        if (obj != null) {
            return obj.getEncoded();
        }
        return new byte[] { '0', 0 };
    }

    private byte[] getEncoded(X509Certificate cert) throws IOException {
        if (cert != null) {
            try {
                return cert.getEncoded();
            } catch (GeneralSecurityException gse) {
                throw new IOException("problem with encoding object in write_X509");
            }
        }
        return new byte[] { '0', 0 };
    }

    private byte[] getEncoded(X509CRL crl) throws IOException {
        if (crl != null) {
            try {
                return crl.getEncoded();
            } catch (GeneralSecurityException gse) {
                throw new IOException("problem with encoding object in write_X509_CRL");
            }
        }
        return new byte[] { '0', 0 };
    }

    @Override
    public void writeDSAPublicKey(Writer _out, DSAPublicKey obj) throws IOException {
        BufferedWriter out = makeBuffered(_out);
        byte[] encoding = getEncoded(obj);
        out.write(BEF_G + PEM_STRING_DSA_PUBLIC + AFT);
        out.newLine();
        writeEncoded(out, encoding);
        out.write(BEF_E + PEM_STRING_DSA_PUBLIC + AFT);
        out.newLine();
        out.flush();
    }
    /** writes an RSA public key encoded in an PKCS#1 RSA structure. */
    @Override
    public void writeRSAPublicKey(Writer _out, RSAPublicKey obj) throws IOException {
        BufferedWriter out = makeBuffered(_out);
        byte[] encoding = getEncoded(obj);
        out.write(BEF_G + PEM_STRING_RSA_PUBLIC + AFT);
        out.newLine();
        writeEncoded(out, encoding);
        out.write(BEF_E + PEM_STRING_RSA_PUBLIC + AFT);
        out.newLine();
        out.flush();
    }
    @Override
    public void writePKCS7(Writer _out, byte[] encoded) throws IOException {
        BufferedWriter out = makeBuffered(_out);
        out.write(BEF_G + PEM_STRING_PKCS7 + AFT);
        out.newLine();
        writeEncoded(out,encoded);
        out.write(BEF_E + PEM_STRING_PKCS7 + AFT);
        out.newLine();
        out.flush();
    }
    @Override
    public void writeX509Certificate(Writer _out, X509Certificate obj) throws IOException {
        BufferedWriter out = makeBuffered(_out);
        byte[] encoding = getEncoded(obj);
        out.write(BEF_G + PEM_STRING_X509 + AFT);
        out.newLine();
        writeEncoded(out, encoding);
        out.write(BEF_E + PEM_STRING_X509 + AFT);
        out.newLine();
        out.flush();
    }

    public void writeX509Aux(Writer _out, X509AuxCertificate obj) throws IOException {
        BufferedWriter out = makeBuffered(_out);
        byte[] encoding = null;
        try {
            if(obj.getAux() == null) {
                encoding = obj.getEncoded();
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] ymp = obj.getEncoded();
                baos.write(ymp,0,ymp.length);
            
                X509Aux aux = obj.getAux();
                ASN1EncodableVector a1 = new ASN1EncodableVector();
                if(aux.trust.size()>0) {
                    ASN1EncodableVector a2 = new ASN1EncodableVector();
                    for(String trust : aux.trust) {
                        a2.add(new DERObjectIdentifier(trust));
                    }
                    a1.add(new DERSequence(a2));
                }
                if(aux.reject.size()>0) {
                    ASN1EncodableVector a2 = new ASN1EncodableVector();
                    for(String reject : aux.reject) {
                        a2.add(new DERObjectIdentifier(reject));
                    }
                    a1.add(new DERTaggedObject(0,new DERSequence(a2)));
                }
                if(aux.alias != null) {
                    a1.add(new DERUTF8String(aux.alias));
                }
                if(aux.keyid != null) {
                    a1.add(new DEROctetString(aux.keyid));
                }
                if(aux.other.size()>0) {
                    ASN1EncodableVector a2 = new ASN1EncodableVector();
                    for(DERObject other : aux.other) {
                        a2.add(other);
                    }
                    a1.add(new DERTaggedObject(1,new DERSequence(a2)));
                }
                ymp = new DERSequence(a1).getEncoded();
                baos.write(ymp,0,ymp.length);
                encoding = baos.toByteArray();
            }
        } catch(CertificateEncodingException e) {
            throw new IOException("problem with encoding object in write_X509_AUX");
        }
        out.write(BEF_G + PEM_STRING_X509_TRUSTED + AFT);
        out.newLine();
        writeEncoded(out,encoding);
        out.write(BEF_E + PEM_STRING_X509_TRUSTED + AFT);
        out.newLine();
        out.flush();
    }
    @Override
    public void writeX509CRL(Writer _out, X509CRL obj) throws IOException {
        BufferedWriter out = makeBuffered(_out);
        byte[] encoding = getEncoded(obj);
        out.write(BEF_G + PEM_STRING_X509_CRL + AFT);
        out.newLine();
        writeEncoded(out, encoding);
        out.write(BEF_E + PEM_STRING_X509_CRL + AFT);
        out.newLine();
        out.flush();
    }

    public void writeX509Request(Writer _out, PKCS10CertificationRequestExt obj) throws IOException {
        BufferedWriter out = makeBuffered(_out);
        byte[] encoding = getEncoded(obj);
        out.write(BEF_G + PEM_STRING_X509_REQ + AFT);
        out.newLine();
        writeEncoded(out,encoding);
        out.write(BEF_E + PEM_STRING_X509_REQ + AFT);
        out.newLine();
        out.flush();
    }

    @Override
    public void writeDSAPrivateKey(Writer _out, DSAPrivateKey obj, String algo, char[] f) throws IOException {
        BufferedWriter out = makeBuffered(_out);
        ByteArrayInputStream    bIn = new ByteArrayInputStream(getEncoded(obj));
        ASN1InputStream         aIn = new ASN1InputStream(bIn);
        PrivateKeyInfo          info = new PrivateKeyInfo((ASN1Sequence)aIn.readObject());
        ByteArrayOutputStream   bOut = new ByteArrayOutputStream();
        ASN1OutputStream        aOut = new ASN1OutputStream(bOut);

        DSAParameter        p = DSAParameter.getInstance(info.getAlgorithmId().getParameters());
        ASN1EncodableVector v = new ASN1EncodableVector();
                
        v.add(new DERInteger(0));
        v.add(new DERInteger(p.getP()));
        v.add(new DERInteger(p.getQ()));
        v.add(new DERInteger(p.getG()));
                
        BigInteger x = obj.getX();
        BigInteger y = p.getG().modPow(x, p.getP());
                
        v.add(new DERInteger(y));
        v.add(new DERInteger(x));
                
        aOut.writeObject(new DERSequence(v));
        byte[] encoding = bOut.toByteArray();

        if(algo != null && f != null) {
            byte[] salt = new byte[8];
            byte[] encData = null;
            random.nextBytes(salt);
            OpenSSLPBEParametersGenerator pGen = new OpenSSLPBEParametersGenerator();
            pGen.init(PBEParametersGenerator.PKCS5PasswordToBytes(f), salt);
            SecretKey secretKey = null;
            if (algo.equalsIgnoreCase("DESede/CBC/PKCS5Padding")) {
                // generate key
                int keyLength = 24;
                KeyParameter param = (KeyParameter) pGen.generateDerivedParameters(keyLength * 8);
                secretKey = new SecretKeySpec(param.getKey(), "DESede");
            } else {
                throw new IOException("unknown algorithm in write_DSAPrivateKey: " + algo);
            }

            // cipher  
            try {
                Cipher c = Cipher.getInstance("DESede/CBC/PKCS5Padding");
                c.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(salt));
                encData = c.doFinal(encoding);
            } catch (Exception e) {
                throw new IOException("exception using cipher: " + e.toString());
            }
       
            // write the data
            out.write(BEF_G + PEM_STRING_DSA + AFT);
            out.newLine();
            out.write("Proc-Type: 4,ENCRYPTED");
            out.newLine();
            out.write("DEK-Info: DES-EDE3-CBC,");
            writeHexEncoded(out,salt);
            out.newLine();
            out.newLine();
            writeEncoded(out,encData);
            out.write(BEF_E + PEM_STRING_DSA + AFT);   
            out.flush();
        } else {
            out.write(BEF_G + PEM_STRING_DSA + AFT);
            out.newLine();
            writeEncoded(out,encoding);
            out.write(BEF_E + PEM_STRING_DSA + AFT);
            out.newLine();
            out.flush();
        }
    }

    @Override
    public void writeRSAPrivateKey(Writer _out, RSAPrivateCrtKey obj, String algo, char[] f) throws IOException {
        assert(obj != null);
        BufferedWriter out = makeBuffered(_out);
        RSAPrivateKeyStructure keyStruct = new RSAPrivateKeyStructure(
                obj.getModulus(),
                obj.getPublicExponent(),
                obj.getPrivateExponent(),
                obj.getPrimeP(),
                obj.getPrimeQ(),
                obj.getPrimeExponentP(),
                obj.getPrimeExponentQ(),
                obj.getCrtCoefficient());
       
        // convert to bytearray
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        ASN1OutputStream      aOut = new ASN1OutputStream(bOut);
            
        aOut.writeObject(keyStruct);
        aOut.close();
        
        byte[] encoding = bOut.toByteArray();

        if(algo != null && f != null) {
            byte[] salt = new byte[8];
            byte[] encData = null;
            random.nextBytes(salt);
            OpenSSLPBEParametersGenerator pGen = new OpenSSLPBEParametersGenerator();
            pGen.init(PBEParametersGenerator.PKCS5PasswordToBytes(f), salt);
            SecretKey secretKey = null;

            if (algo.startsWith("DES")) {
                // generate key
                int keyLength = 24;
                if (algo.equalsIgnoreCase("DESEDE")) {
                    algo = "DESede/CBC/PKCS5Padding";
                }
                KeyParameter param = (KeyParameter) pGen.generateDerivedParameters(keyLength * 8);
                secretKey = new SecretKeySpec(param.getKey(), algo.split("/")[0]);
            } else {
                throw new IOException("unknown algorithm `" + algo + "' in write_DSAPrivateKey");
            }

            // cipher  
            try {
                Cipher c = Cipher.getInstance(algo);
                c.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(salt));
                encData = c.doFinal(encoding);
            } catch (Exception e) {
                throw new IOException("exception using cipher: " + e.toString());
            }
       
            // write the data
            out.write(BEF_G + PEM_STRING_RSA + AFT);
            out.newLine();
            out.write("Proc-Type: 4,ENCRYPTED");
            out.newLine();
            out.write("DEK-Info: DES-EDE3-CBC,");
            writeHexEncoded(out,salt);
            out.newLine();
            out.newLine();
            writeEncoded(out,encData);
            out.write(BEF_E + PEM_STRING_RSA + AFT);   
            out.flush();
        } else {
            out.write(BEF_G + PEM_STRING_RSA + AFT);
            out.newLine();
            writeEncoded(out,encoding);
            out.write(BEF_E + PEM_STRING_RSA + AFT);
            out.newLine();
            out.flush();
        }
    }
    
    @Override
    public void writeDHParameters(Writer _out, DHParameterSpec params) throws IOException {
        BufferedWriter out = makeBuffered(_out);
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        ASN1OutputStream aOut = new ASN1OutputStream(bOut);

        ASN1EncodableVector v = new ASN1EncodableVector();

        BigInteger value;
        if ((value = params.getP()) != null) {
            v.add(new DERInteger(value));
        }
        if ((value = params.getG()) != null) {
            v.add(new DERInteger(value));
        }

        aOut.writeObject(new DERSequence(v));
        byte[] encoding = bOut.toByteArray();

        out.write(BEF_G + PEM_STRING_DHPARAMS + AFT);
        out.newLine();
        writeEncoded(out,encoding);
        out.write(BEF_E + PEM_STRING_DHPARAMS + AFT);
        out.newLine();
        out.flush();
    }

    private String getPrivateKeyTypeFromObjectId(DERObjectIdentifier oid) {
        if (ASN1Registry.obj2nid(oid) == ASN1Registry.NID_rsaEncryption) {
            return "RSA";
        } else {
            return "DSA";
        }
    }

    private byte[] readBytes(BufferedReader in, String endMarker) throws IOException {
        String          line;
        StringBuffer    buf = new StringBuffer();
  
        while ((line = in.readLine()) != null) {
            if (line.indexOf(endMarker) != -1) {
                break;
            }
            buf.append(line.trim());
        }

        if (line == null) {
            throw new IOException(endMarker + " not found");
        }

        return Base64.decode(buf.toString());
    }

    /**
     * create the secret key needed for this object, fetching the password
     */
    private SecretKey getKey(char[] k1, String algorithm, int keyLength, byte[] salt) throws IOException {
        char[] password = k1;
        if (password == null) {
            throw new IOException("Password is null, but a password is required");
        }
        OpenSSLPBEParametersGenerator pGen = new OpenSSLPBEParametersGenerator();
        pGen.init(PBEParametersGenerator.PKCS5PasswordToBytes(password), salt);
        KeyParameter param = (KeyParameter) pGen.generateDerivedParameters(keyLength * 8);
        return new javax.crypto.spec.SecretKeySpec(param.getKey(), algorithm);
    }

    private RSAPublicKey readRSAPublicKey(BufferedReader in, String endMarker) throws IOException {
        ByteArrayInputStream bAIS = new ByteArrayInputStream(readBytes(in,endMarker));
        ASN1InputStream ais = new ASN1InputStream(bAIS);
        Object asnObject = ais.readObject();
        ASN1Sequence sequence = (ASN1Sequence) asnObject;
        RSAPublicKeyStructure rsaPubStructure = new RSAPublicKeyStructure(sequence);
        RSAPublicKeySpec keySpec = new RSAPublicKeySpec(
                    rsaPubStructure.getModulus(), 
                    rsaPubStructure.getPublicExponent());

        try {
            KeyFactory keyFact = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) keyFact.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException e) { 
                // ignore
        } catch (InvalidKeySpecException e) { 
                // ignore
        }

        return  null;
    }

    private PublicKey readPublicKey(BufferedReader in, String alg, String endMarker) throws IOException {
        KeySpec keySpec = new X509EncodedKeySpec(readBytes(in,endMarker));
        try {
            KeyFactory keyFact = KeyFactory.getInstance(alg);
            PublicKey pubKey = keyFact.generatePublic(keySpec);
            return pubKey;
        } catch (NoSuchAlgorithmException e) { 
            // ignore
        } catch (InvalidKeySpecException e) { 
            // ignore
        }
        return null;
    }

    private PublicKey readPublicKey(BufferedReader in, String endMarker) throws IOException {
        KeySpec keySpec = new X509EncodedKeySpec(readBytes(in,endMarker));
        String[] algs = {"RSA","DSA"};
        for(int i=0;i<algs.length;i++) {
            try {
                KeyFactory keyFact = KeyFactory.getInstance(algs[i]);
                PublicKey pubKey = keyFact.generatePublic(keySpec);
                return pubKey;
            } catch (NoSuchAlgorithmException e) { 
                // ignore
            } catch (InvalidKeySpecException e) { 
                // ignore
            }
        }
        return null;
    }

    /**
     * Read a Key Pair
     */
    private KeyPair readKeyPair(BufferedReader _in, char[] passwd, String type,String endMarker)
        throws Exception {
        boolean         isEncrypted = false;
        String          line = null;
        String          dekInfo = null;
        StringBuffer    buf = new StringBuffer();

        while ((line = _in.readLine()) != null) {
            if (line.startsWith("Proc-Type: 4,ENCRYPTED")) {
                isEncrypted = true;
            } else if (line.startsWith("DEK-Info:")) {
                dekInfo = line.substring(10);
            } else if (line.indexOf(endMarker) != -1) {
                break;
            } else {
                buf.append(line.trim());
            }
        }
        byte[]  keyBytes = null;
        if (isEncrypted) {
            StringTokenizer tknz = new StringTokenizer(dekInfo, ",");
            String          encoding = tknz.nextToken();

            if (encoding.equals("DES-EDE3-CBC")) {
                String  alg = "DESede";
                byte[]  iv = Hex.decode(tknz.nextToken());
                Key     sKey = getKey(passwd,alg, 24, iv);
                Cipher  c = Cipher.getInstance("DESede/CBC/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE, sKey, new IvParameterSpec(iv));
                keyBytes = c.doFinal(Base64.decode(buf.toString()));
            } else if (encoding.equals("DES-CBC")) {
                String  alg = "DES";
                byte[]  iv = Hex.decode(tknz.nextToken());
                Key     sKey = getKey(passwd,alg, 8, iv);
                Cipher  c = Cipher.getInstance("DES/CBC/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE, sKey, new IvParameterSpec(iv));
                keyBytes = c.doFinal(Base64.decode(buf.toString()));
            } else {
                throw new IOException("unknown encryption with private key");
            }
        } else {
            keyBytes = Base64.decode(buf.toString());
        }
        return readPrivateKeySequence(keyBytes, type);
    }

    private KeyPair readPrivateKeySequence(byte[] in, String type) throws Exception {
        KeySpec pubSpec = null;
        KeySpec privSpec = null;
        ByteArrayInputStream bIn = new ByteArrayInputStream(in);
        ASN1InputStream aIn = new ASN1InputStream(bIn);
        ASN1Sequence seq = (ASN1Sequence) aIn.readObject();
        if (type.equals("RSA")) {
            DERInteger mod = (DERInteger) seq.getObjectAt(1);
            DERInteger pubExp = (DERInteger) seq.getObjectAt(2);
            DERInteger privExp = (DERInteger) seq.getObjectAt(3);
            DERInteger p1 = (DERInteger) seq.getObjectAt(4);
            DERInteger p2 = (DERInteger) seq.getObjectAt(5);
            DERInteger exp1 = (DERInteger) seq.getObjectAt(6);
            DERInteger exp2 = (DERInteger) seq.getObjectAt(7);
            DERInteger crtCoef = (DERInteger) seq.getObjectAt(8);
            pubSpec = new RSAPublicKeySpec(mod.getValue(), pubExp.getValue());
            privSpec = new RSAPrivateCrtKeySpec(mod.getValue(), pubExp.getValue(), privExp.getValue(), p1.getValue(), p2.getValue(), exp1.getValue(), exp2.getValue(), crtCoef.getValue());
        } else { // assume "DSA" for now.
            DERInteger p = (DERInteger) seq.getObjectAt(1);
            DERInteger q = (DERInteger) seq.getObjectAt(2);
            DERInteger g = (DERInteger) seq.getObjectAt(3);
            DERInteger y = (DERInteger) seq.getObjectAt(4);
            DERInteger x = (DERInteger) seq.getObjectAt(5);
            privSpec = new DSAPrivateKeySpec(x.getValue(), p.getValue(), q.getValue(), g.getValue());
            pubSpec = new DSAPublicKeySpec(y.getValue(), p.getValue(), q.getValue(), g.getValue());
        }
        KeyFactory fact = KeyFactory.getInstance(type);
        return new KeyPair(fact.generatePublic(pubSpec), fact.generatePrivate(privSpec));
    }

    /**
     * Reads in a X509Certificate.
     *
     * @return the X509Certificate
     * @throws IOException if an I/O error occured
     */
    private X509Certificate readCertificate(BufferedReader in,String  endMarker) throws IOException {
        String          line;
        StringBuffer    buf = new StringBuffer();
  
        while ((line = in.readLine()) != null)
        {
            if (line.indexOf(endMarker) != -1)
            {
                break;
            }
            buf.append(line.trim());
        }

        if (line == null)
        {
            throw new IOException(endMarker + " not found");
        }

        ByteArrayInputStream    bIn = new ByteArrayInputStream(
                                                Base64.decode(buf.toString()));

        try
        {
            CertificateFactory certFact
                    = CertificateFactory.getInstance("X.509");

            return (X509Certificate)certFact.generateCertificate(bIn);
        }
        catch (Exception e)
        {
            throw new IOException("problem parsing cert: " + e.toString());
        }
    }

    private X509AuxCertificate readAuxCertificate(BufferedReader in,String  endMarker) throws IOException {
        String          line;
        StringBuffer    buf = new StringBuffer();
  
        while ((line = in.readLine()) != null) {
            if (line.indexOf(endMarker) != -1) {
                break;
            }
            buf.append(line.trim());
        }

        if (line == null) {
            throw new IOException(endMarker + " not found");
        }

        ASN1InputStream try1 = new ASN1InputStream(Base64.decode(buf.toString()));
        ByteArrayInputStream bIn = new ByteArrayInputStream((try1.readObject()).getEncoded());

        try {
            CertificateFactory certFact = CertificateFactory.getInstance("X.509");
            X509Certificate bCert = (X509Certificate)certFact.generateCertificate(bIn);
            DERSequence aux = (DERSequence)try1.readObject();
            X509Aux ax = null;
            if(aux != null) {
                ax = new X509Aux();
                int ix = 0;
                if(aux.size() > ix && aux.getObjectAt(ix) instanceof DERSequence) {
                    DERSequence trust = (DERSequence)aux.getObjectAt(ix++);
                    for(int i=0;i<trust.size();i++) {
                        ax.trust.add(((DERObjectIdentifier)trust.getObjectAt(i)).getId());
                    }
                }
                if(aux.size() > ix && aux.getObjectAt(ix) instanceof DERTaggedObject && ((DERTaggedObject)aux.getObjectAt(ix)).getTagNo() == 0) {
                    DERSequence reject = (DERSequence)((DERTaggedObject)aux.getObjectAt(ix++)).getObject();
                    for(int i=0;i<reject.size();i++) {
                        ax.reject.add(((DERObjectIdentifier)reject.getObjectAt(i)).getId());
                    }
                }
                if(aux.size()>ix && aux.getObjectAt(ix) instanceof DERUTF8String) {
                    ax.alias = ((DERUTF8String)aux.getObjectAt(ix++)).getString();
                }
                if(aux.size()>ix && aux.getObjectAt(ix) instanceof DEROctetString) {
                    ax.keyid = ((DEROctetString)aux.getObjectAt(ix++)).getOctets();
                }
                if(aux.size() > ix && aux.getObjectAt(ix) instanceof DERTaggedObject && ((DERTaggedObject)aux.getObjectAt(ix)).getTagNo() == 1) {
                    DERSequence other = (DERSequence)((DERTaggedObject)aux.getObjectAt(ix++)).getObject();
                    for(int i=0;i<other.size();i++) {
                        ax.other.add((DERObject)(other.getObjectAt(i)));
                    }
                }
            }
            return new X509AuxCertificate(bCert,ax);
        } catch (Exception e) {
            throw new IOException("problem parsing cert: " + e.toString());
        }
    }

    /**
     * Reads in a X509CRL.
     *
     * @return the X509CRL
     * @throws IOException if an I/O error occured
     */
    private X509CRL readCRL(BufferedReader in, String  endMarker) throws IOException {
        String          line;
        StringBuffer    buf = new StringBuffer();
  
        while ((line = in.readLine()) != null)
        {
            if (line.indexOf(endMarker) != -1)
            {
                break;
            }
            buf.append(line.trim());
        }

        if (line == null)
        {
            throw new IOException(endMarker + " not found");
        }

        ByteArrayInputStream    bIn = new ByteArrayInputStream(
                                                Base64.decode(buf.toString()));

        try
        {
            CertificateFactory certFact
                    = CertificateFactory.getInstance("X.509");

            return (X509CRL)certFact.generateCRL(bIn);
        }
        catch (Exception e)
        {
            throw new IOException("problem parsing cert: " + e.toString());
        }
    }

    /**
     * Reads in a PKCS10 certification request.
     *
     * @return the certificate request.
     * @throws IOException if an I/O error occured
     */
    private PKCS10CertificationRequestExt readCertificateRequest(BufferedReader in, String  endMarker) throws IOException {
        String          line;
        StringBuffer    buf = new StringBuffer();
  
        while ((line = in.readLine()) != null)
        {
            if (line.indexOf(endMarker) != -1)
            {
                break;
            }
            buf.append(line.trim());
        }

        if (line == null)
        {
            throw new IOException(endMarker + " not found");
        }

        try
        {
            return new PKCS10CertificationRequestExt(Base64.decode(buf.toString()));
        }
        catch (Exception e)
        {
            throw new IOException("problem parsing cert: " + e.toString());
        }
    }

    private void writeHexEncoded(BufferedWriter out, byte[] bytes) throws IOException {
        bytes = Hex.encode(bytes);
        for (int i = 0; i != bytes.length; i++) {
            out.write((char)bytes[i]);
        }
    }

    private void writeEncoded(BufferedWriter out, byte[] bytes) throws IOException {
        char[]  buf = new char[64];
        bytes = Base64.encode(bytes);
        for (int i = 0; i < bytes.length; i += buf.length) {
            int index = 0;
            
            while (index != buf.length) {
                if ((i + index) >= bytes.length) {
                    break;
                }
                buf[index] = (char)bytes[i + index];
                index++;
            }
            out.write(buf, 0, index);
            out.newLine();
        }
    }
    
    // d2i_RSAPrivateKey_bio
    @Override
    public PrivateKey readRSAPrivateKey(String input) throws IOException, GeneralSecurityException {
        KeyFactory fact = KeyFactory.getInstance("RSA");
        DERSequence seq = (DERSequence) (new ASN1InputStream(ByteList.plain(input)).readObject());
        if (seq.size() == 9) {
            BigInteger mod = ((DERInteger) seq.getObjectAt(1)).getValue();
            BigInteger pubexp = ((DERInteger) seq.getObjectAt(2)).getValue();
            BigInteger privexp = ((DERInteger) seq.getObjectAt(3)).getValue();
            BigInteger primep = ((DERInteger) seq.getObjectAt(4)).getValue();
            BigInteger primeq = ((DERInteger) seq.getObjectAt(5)).getValue();
            BigInteger primeep = ((DERInteger) seq.getObjectAt(6)).getValue();
            BigInteger primeeq = ((DERInteger) seq.getObjectAt(7)).getValue();
            BigInteger crtcoeff = ((DERInteger) seq.getObjectAt(8)).getValue();
            return fact.generatePrivate(new RSAPrivateCrtKeySpec(mod, pubexp, privexp, primep, primeq, primeep, primeeq, crtcoeff));
        } else {
            return null;
        }
    }

    // d2i_RSAPublicKey_bio
    @Override
    public PublicKey readRSAPublicKey(String input) throws IOException, GeneralSecurityException {
        KeyFactory fact = KeyFactory.getInstance("RSA");
        DERSequence seq = (DERSequence) (new ASN1InputStream(ByteList.plain(input)).readObject());
        if (seq.size() == 2) {
            BigInteger mod = ((DERInteger) seq.getObjectAt(0)).getValue();
            BigInteger pubexp = ((DERInteger) seq.getObjectAt(1)).getValue();
            return fact.generatePublic(new RSAPublicKeySpec(mod, pubexp));
        } else {
            return null;
        }
    }

    // d2i_DSAPrivateKey_bio
    @Override
    public KeyPair readDSAPrivateKey(String input) throws IOException, GeneralSecurityException {
        KeyFactory fact = KeyFactory.getInstance("DSA");
        DERSequence seq = (DERSequence) (new ASN1InputStream(ByteList.plain(input)).readObject());
        if (seq.size() == 6) {
            BigInteger p = ((DERInteger) seq.getObjectAt(1)).getValue();
            BigInteger q = ((DERInteger) seq.getObjectAt(2)).getValue();
            BigInteger g = ((DERInteger) seq.getObjectAt(3)).getValue();
            BigInteger y = ((DERInteger) seq.getObjectAt(4)).getValue();
            BigInteger x = ((DERInteger) seq.getObjectAt(5)).getValue();
            PrivateKey priv = fact.generatePrivate(new DSAPrivateKeySpec(x, p, q, g));
            PublicKey pub = fact.generatePublic(new DSAPublicKeySpec(y, p, q, g));
            return new KeyPair(pub, priv);
        } else {
            return null;
        }
    }

    // d2i_DSA_PUBKEY_bio
    @Override
    public PublicKey readDSAPublicKey(String input) throws IOException, GeneralSecurityException {
        KeyFactory fact = KeyFactory.getInstance("RSA");
        DERSequence seq = (DERSequence) (new ASN1InputStream(ByteList.plain(input)).readObject());
        if (seq.size() == 4) {
            BigInteger y = ((DERInteger) seq.getObjectAt(0)).getValue();
            BigInteger p = ((DERInteger) seq.getObjectAt(1)).getValue();
            BigInteger q = ((DERInteger) seq.getObjectAt(2)).getValue();
            BigInteger g = ((DERInteger) seq.getObjectAt(3)).getValue();
            return fact.generatePublic(new DSAPublicKeySpec(y, p, q, g));
        } else {
            return null;
        }
    }

    @Override
    public byte[] toDerRSAKey(RSAPublicKey pubKey, RSAPrivateCrtKey privKey) throws IOException {
        ASN1EncodableVector v1 = new ASN1EncodableVector();
        if (pubKey != null && privKey == null) {
            v1.add(new DERInteger(pubKey.getModulus()));
            v1.add(new DERInteger(pubKey.getPublicExponent()));
        } else {
            v1.add(new DERInteger(0));
            v1.add(new DERInteger(privKey.getModulus()));
            v1.add(new DERInteger(privKey.getPublicExponent()));
            v1.add(new DERInteger(privKey.getPrivateExponent()));
            v1.add(new DERInteger(privKey.getPrimeP()));
            v1.add(new DERInteger(privKey.getPrimeQ()));
            v1.add(new DERInteger(privKey.getPrimeExponentP()));
            v1.add(new DERInteger(privKey.getPrimeExponentQ()));
            v1.add(new DERInteger(privKey.getCrtCoefficient()));
        }
        return new DERSequence(v1).getEncoded();
    }

    @Override
    public byte[] toDerDSAKey(DSAPublicKey pubKey, DSAPrivateKey privKey) throws IOException {
        if (pubKey != null && privKey == null) {
            return pubKey.getEncoded();
        } else if (privKey != null && pubKey != null) {
            DSAParams params = privKey.getParams();
            ASN1EncodableVector v1 = new ASN1EncodableVector();
            v1.add(new DERInteger(0));
            v1.add(new DERInteger(params.getP()));
            v1.add(new DERInteger(params.getQ()));
            v1.add(new DERInteger(params.getG()));
            v1.add(new DERInteger(pubKey.getY()));
            v1.add(new DERInteger(privKey.getX()));
            return new DERSequence(v1).getEncoded();
        } else {
            return privKey.getEncoded();
        }
    }

    @Override
    public byte[] toDerDHKey(BigInteger p, BigInteger g) throws IOException {
        ASN1EncodableVector v = new ASN1EncodableVector();
        if (p != null) {
            v.add(new DERInteger(p));
        }
        if (g != null) {
            v.add(new DERInteger(g));
        }
        return new DERSequence(v).getEncoded();
    }
}// PEM
