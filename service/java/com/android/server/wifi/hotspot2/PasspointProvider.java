/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi.hotspot2;

import android.annotation.Nullable;
import android.net.wifi.EAPConstants;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.Credential.SimCredential;
import android.net.wifi.hotspot2.pps.Credential.UserCredential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import com.android.internal.util.ArrayUtils;
import com.android.server.wifi.IMSIParameter;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType;
import com.android.server.wifi.hotspot2.anqp.DomainNameElement;
import com.android.server.wifi.hotspot2.anqp.NAIRealmElement;
import com.android.server.wifi.hotspot2.anqp.RoamingConsortiumElement;
import com.android.server.wifi.hotspot2.anqp.ThreeGPPNetworkElement;
import com.android.server.wifi.hotspot2.anqp.eap.AuthParam;
import com.android.server.wifi.hotspot2.anqp.eap.NonEAPInnerAuth;
import com.android.server.wifi.util.InformationElementUtil.RoamingConsortium;
import com.android.server.wifi.util.TelephonyUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Abstraction for Passpoint service provider.  This class contains the both static
 * Passpoint configuration data and the runtime data (e.g. blacklisted SSIDs, statistics).
 */
public class PasspointProvider {
    private static final String TAG = "PasspointProvider";

    /**
     * Used as part of alias string for certificates and keys.  The alias string is in the format
     * of: [KEY_TYPE]_HS2_[ProviderID]
     * For example: "CACERT_HS2_0", "USRCERT_HS2_0", "USRPKEY_HS2_0", "CACERT_HS2_REMEDIATION_0"
     */
    private static final String ALIAS_HS_TYPE = "HS2_";
    private static final String ALIAS_ALIAS_REMEDIATION_TYPE = "REMEDIATION_";

    private static final String SYSTEM_CA_STORE_PATH = "/system/etc/security/cacerts";

    private final PasspointConfiguration mConfig;
    private final WifiKeyStore mKeyStore;

    /**
     * Aliases for the private keys and certificates installed in the keystore.  Each alias
     * is a suffix of the actual certificate or key name installed in the keystore.  The
     * certificate or key name in the keystore is consist of |Type|_|alias|.
     * This will be consistent with the usage of the term "alias" in {@link WifiEnterpriseConfig}.
     */
    private List<String> mCaCertificateAliases;
    private String mClientPrivateKeyAndCertificateAlias;
    private String mRemediationCaCertificateAlias;

    private final long mProviderId;
    private final int mCreatorUid;
    private final String mPackageName;

    private final IMSIParameter mImsiParameter;

    private final int mEAPMethodID;
    private final AuthParam mAuthParam;
    private final TelephonyUtil mTelephonyUtil;

    private int mBestGuessCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;
    private boolean mHasEverConnected;
    private boolean mIsShared;
    private boolean mIsFromSuggestion;


    public PasspointProvider(PasspointConfiguration config, WifiKeyStore keyStore,
            TelephonyUtil telephonyUtil, long providerId, int creatorUid, String packageName,
            boolean isFromSuggestion) {
        this(config, keyStore, telephonyUtil, providerId, creatorUid, packageName, isFromSuggestion,
                null, null, null, false, false);
    }

    public PasspointProvider(PasspointConfiguration config, WifiKeyStore keyStore,
            TelephonyUtil telephonyUtil, long providerId, int creatorUid, String packageName,
            boolean isFromSuggestion, List<String> caCertificateAliases,
            String clientPrivateKeyAndCertificateAlias,
            String remediationCaCertificateAlias,
            boolean hasEverConnected, boolean isShared) {
        // Maintain a copy of the configuration to avoid it being updated by others.
        mConfig = new PasspointConfiguration(config);
        mKeyStore = keyStore;
        mProviderId = providerId;
        mCreatorUid = creatorUid;
        mPackageName = packageName;
        mCaCertificateAliases = caCertificateAliases;
        mClientPrivateKeyAndCertificateAlias = clientPrivateKeyAndCertificateAlias;
        mRemediationCaCertificateAlias = remediationCaCertificateAlias;
        mHasEverConnected = hasEverConnected;
        mIsShared = isShared;
        mIsFromSuggestion = isFromSuggestion;
        mTelephonyUtil = telephonyUtil;

        // Setup EAP method and authentication parameter based on the credential.
        if (mConfig.getCredential().getUserCredential() != null) {
            mEAPMethodID = EAPConstants.EAP_TTLS;
            mAuthParam = new NonEAPInnerAuth(NonEAPInnerAuth.getAuthTypeID(
                    mConfig.getCredential().getUserCredential().getNonEapInnerMethod()));
            mImsiParameter = null;
        } else if (mConfig.getCredential().getCertCredential() != null) {
            mEAPMethodID = EAPConstants.EAP_TLS;
            mAuthParam = null;
            mImsiParameter = null;
        } else {
            mEAPMethodID = mConfig.getCredential().getSimCredential().getEapType();
            mAuthParam = null;
            mImsiParameter = IMSIParameter.build(
                    mConfig.getCredential().getSimCredential().getImsi());
        }
    }

    public PasspointConfiguration getConfig() {
        // Return a copy of the configuration to avoid it being updated by others.
        return new PasspointConfiguration(mConfig);
    }

    public List<String> getCaCertificateAliases() {
        return mCaCertificateAliases;
    }

    public String getClientPrivateKeyAndCertificateAlias() {
        return mClientPrivateKeyAndCertificateAlias;
    }

    public String getRemediationCaCertificateAlias() {
        return mRemediationCaCertificateAlias;
    }

    public long getProviderId() {
        return mProviderId;
    }

    public int getCreatorUid() {
        return mCreatorUid;
    }

    @Nullable
    public String getPackageName() {
        return mPackageName;
    }

    public boolean getHasEverConnected() {
        return mHasEverConnected;
    }

    public void setHasEverConnected(boolean hasEverConnected) {
        mHasEverConnected = hasEverConnected;
    }

    public boolean isFromSuggestion() {
        return mIsFromSuggestion;
    }

    /**
     * Install certificates and key based on current configuration.
     * Note: the certificates and keys in the configuration will get cleared once
     * they're installed in the keystore.
     *
     * @return true on success
     */
    public boolean installCertsAndKeys() {
        // Install CA certificate.
        X509Certificate[] x509Certificates = mConfig.getCredential().getCaCertificates();
        if (x509Certificates != null) {
            mCaCertificateAliases = new ArrayList<>();
            for (int i = 0; i < x509Certificates.length; i++) {
                String alias = String.format("%s%s_%d", ALIAS_HS_TYPE, mProviderId, i);
                if (!mKeyStore.putCaCertInKeyStore(alias, x509Certificates[i])) {
                    Log.e(TAG, "Failed to install CA Certificate");
                    uninstallCertsAndKeys();
                    return false;
                } else {
                    mCaCertificateAliases.add(alias);
                }
            }
        }

        // Install the client private key & certificate.
        if (mConfig.getCredential().getClientPrivateKey() != null
                && mConfig.getCredential().getClientCertificateChain() != null) {
            String keyName = ALIAS_HS_TYPE + mProviderId;
            PrivateKey clientKey = mConfig.getCredential().getClientPrivateKey();
            X509Certificate clientCert = getClientCertificate(
                    mConfig.getCredential().getClientCertificateChain(),
                    mConfig.getCredential().getCertCredential().getCertSha256Fingerprint());
            if (clientCert == null) {
                Log.e(TAG, "Failed to locate client certificate");
                uninstallCertsAndKeys();
                return false;
            }
            if (!mKeyStore.putUserPrivKeyAndCertsInKeyStore(
                    keyName, clientKey, new Certificate[] {clientCert})) {
                Log.e(TAG, "Failed to install client private key & certificate");
                uninstallCertsAndKeys();
                return false;
            }
            mClientPrivateKeyAndCertificateAlias = keyName;
        }

        if (mConfig.getSubscriptionUpdate() != null) {
            X509Certificate certificate = mConfig.getSubscriptionUpdate().getCaCertificate();
            if (certificate == null) {
                Log.e(TAG, "Failed to locate CA certificate for remediation");
                uninstallCertsAndKeys();
                return false;
            }
            String certName = ALIAS_HS_TYPE + ALIAS_ALIAS_REMEDIATION_TYPE + mProviderId;
            if (!mKeyStore.putCaCertInKeyStore(certName, certificate)) {
                Log.e(TAG, "Failed to install CA certificate for remediation");
                uninstallCertsAndKeys();
                return false;
            }
            mRemediationCaCertificateAlias = certName;
        }

        // Clear the keys and certificates in the configuration.
        mConfig.getCredential().setCaCertificates(null);
        mConfig.getCredential().setClientPrivateKey(null);
        mConfig.getCredential().setClientCertificateChain(null);
        if (mConfig.getSubscriptionUpdate() != null) {
            mConfig.getSubscriptionUpdate().setCaCertificate(null);
        }
        return true;
    }

    /**
     * Remove any installed certificates and key.
     */
    public void uninstallCertsAndKeys() {
        if (mCaCertificateAliases != null) {
            for (String certificateAlias : mCaCertificateAliases) {
                if (!mKeyStore.removeEntryFromKeyStore(certificateAlias)) {
                    Log.e(TAG, "Failed to remove entry: " + certificateAlias);
                }
            }
            mCaCertificateAliases = null;
        }
        if (mClientPrivateKeyAndCertificateAlias != null) {
            if (!mKeyStore.removeEntryFromKeyStore(mClientPrivateKeyAndCertificateAlias)) {
                Log.e(TAG, "Failed to remove entry: " + mClientPrivateKeyAndCertificateAlias);
            }
            mClientPrivateKeyAndCertificateAlias = null;
        }
        if (mRemediationCaCertificateAlias != null) {
            if (!mKeyStore.removeEntryFromKeyStore(mRemediationCaCertificateAlias)) {
                Log.e(TAG, "Failed to remove entry: " + mRemediationCaCertificateAlias);
            }
            mRemediationCaCertificateAlias = null;
        }
    }

    /**
     * Try to update the carrier ID according to the IMSI parameter of passpoint configuration.
     *
     * @return true if the carrier ID is updated, otherwise false.
     */
    public boolean tryUpdateCarrierId() {
        return mTelephonyUtil.tryUpdateCarrierIdForPasspoint(mConfig);
    }

    private @Nullable String getMatchingSimImsi() {
        String matchingSIMImsi = null;
        if (mConfig.getCarrierId() != TelephonyManager.UNKNOWN_CARRIER_ID) {
            matchingSIMImsi = mTelephonyUtil
                    .getMatchingImsi(mConfig.getCarrierId());
        } else {
            // Get the IMSI and carrier ID of SIM card which match with the IMSI prefix from
            // passpoint profile
            Pair<String, Integer> imsiCarrierIdPair = mTelephonyUtil.getMatchingImsiCarrierId(
                    mConfig.getCredential().getSimCredential().getImsi());
            if (imsiCarrierIdPair != null) {
                matchingSIMImsi = imsiCarrierIdPair.first;
                mBestGuessCarrierId = imsiCarrierIdPair.second;
            }
        }

        return matchingSIMImsi;
    }

    /**
     * Return the matching status with the given AP, based on the ANQP elements from the AP.
     *
     * @param anqpElements ANQP elements from the AP
     * @param roamingConsortium Roaming Consortium information element from the AP
     * @return {@link PasspointMatch}
     */
    public PasspointMatch match(Map<ANQPElementType, ANQPElement> anqpElements,
            RoamingConsortium roamingConsortium) {

        String matchingSimImsi = null;
        if (mConfig.getCredential().getSimCredential() != null) {
            matchingSimImsi = getMatchingSimImsi();
            if (TextUtils.isEmpty(matchingSimImsi)) {
                Log.d(TAG, "No SIM card for this profile with SIM credential.");
                return PasspointMatch.None;
            }
        }

        PasspointMatch providerMatch = matchProviderExceptFor3GPP(
                anqpElements, roamingConsortium, matchingSimImsi);

        // 3GPP Network matching.
        if (providerMatch == PasspointMatch.None && ANQPMatcher.matchThreeGPPNetwork(
                (ThreeGPPNetworkElement) anqpElements.get(ANQPElementType.ANQP3GPPNetwork),
                mImsiParameter, matchingSimImsi)) {
            return PasspointMatch.RoamingProvider;
        }

        // Perform authentication match against the NAI Realm.
        int authMatch = ANQPMatcher.matchNAIRealm(
                (NAIRealmElement) anqpElements.get(ANQPElementType.ANQPNAIRealm),
                mConfig.getCredential().getRealm(), mEAPMethodID, mAuthParam);

        // In case of Auth mismatch, demote provider match.
        if (authMatch == AuthMatch.NONE) {
            return PasspointMatch.None;
        }

        // In case of no realm match, return provider match as is.
        if ((authMatch & AuthMatch.REALM) == 0) {
            return providerMatch;
        }

        // Promote the provider match to roaming provider if provider match is not found, but NAI
        // realm is matched.
        return providerMatch == PasspointMatch.None ? PasspointMatch.RoamingProvider
                : providerMatch;
    }

    /**
     * Generate a WifiConfiguration based on the provider's configuration.  The generated
     * WifiConfiguration will include all the necessary credentials for network connection except
     * the SSID, which should be added by the caller when the config is being used for network
     * connection.
     *
     * @return {@link WifiConfiguration}
     */
    public WifiConfiguration getWifiConfig() {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = mConfig.getHomeSp().getFqdn();
        if (mConfig.getHomeSp().getRoamingConsortiumOis() != null) {
            wifiConfig.roamingConsortiumIds = Arrays.copyOf(
                    mConfig.getHomeSp().getRoamingConsortiumOis(),
                    mConfig.getHomeSp().getRoamingConsortiumOis().length);
        }
        if (mConfig.getUpdateIdentifier() != Integer.MIN_VALUE) {
            // R2 profile, it needs to set updateIdentifier HS2.0 Indication element as PPS MO
            // ID in Association Request.
            wifiConfig.updateIdentifier = Integer.toString(mConfig.getUpdateIdentifier());
            if (isMeteredNetwork(mConfig)) {
                wifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_METERED;
            }
        }
        wifiConfig.providerFriendlyName = mConfig.getHomeSp().getFriendlyName();
        wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);
        int carrierId = mConfig.getCarrierId();
        if (carrierId == TelephonyManager.UNKNOWN_CARRIER_ID) {
            carrierId = mBestGuessCarrierId;
        }
        wifiConfig.carrierId = carrierId;

        // Set RSN only to tell wpa_supplicant that this network is for Passpoint.
        wifiConfig.allowedProtocols.set(WifiConfiguration.Protocol.RSN);

        WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
        enterpriseConfig.setRealm(mConfig.getCredential().getRealm());
        enterpriseConfig.setDomainSuffixMatch(mConfig.getHomeSp().getFqdn());
        if (mConfig.getCredential().getUserCredential() != null) {
            buildEnterpriseConfigForUserCredential(enterpriseConfig,
                    mConfig.getCredential().getUserCredential());
            setAnonymousIdentityToNaiRealm(enterpriseConfig, mConfig.getCredential().getRealm());
        } else if (mConfig.getCredential().getCertCredential() != null) {
            buildEnterpriseConfigForCertCredential(enterpriseConfig);
            setAnonymousIdentityToNaiRealm(enterpriseConfig, mConfig.getCredential().getRealm());
        } else {
            buildEnterpriseConfigForSimCredential(enterpriseConfig,
                    mConfig.getCredential().getSimCredential());
        }
        // If AAA server trusted names are specified, use it to replace HOME SP FQDN
        // and use system CA regardless of provisioned CA certificate.
        if (!ArrayUtils.isEmpty(mConfig.getAaaServerTrustedNames())) {
            enterpriseConfig.setDomainSuffixMatch(
                    String.join(";", mConfig.getAaaServerTrustedNames()));
            enterpriseConfig.setCaCertificateAliases(new String[] {SYSTEM_CA_STORE_PATH});
        }
        wifiConfig.enterpriseConfig = enterpriseConfig;
        // PPS MO Credential/CheckAAAServerCertStatus node contains a flag which indicates
        // if the mobile device needs to check the AAA server certificate's revocation status
        // during EAP authentication.
        if (mConfig.getCredential().getCheckAaaServerCertStatus()) {
            // Check server certificate using OCSP (Online Certificate Status Protocol).
            wifiConfig.enterpriseConfig.setOcsp(WifiEnterpriseConfig.OCSP_REQUIRE_CERT_STATUS);
        }
        wifiConfig.shared = mIsShared;
        wifiConfig.fromWifiNetworkSuggestion = mIsFromSuggestion;
        wifiConfig.ephemeral = mIsFromSuggestion;
        wifiConfig.creatorName = mPackageName;
        wifiConfig.creatorUid = mCreatorUid;
        return wifiConfig;
    }

    /**
     * @return true if provider is backed by a SIM credential.
     */
    public boolean isSimCredential() {
        return mConfig.getCredential().getSimCredential() != null;
    }

    /**
     * Convert a legacy {@link WifiConfiguration} representation of a Passpoint configuration to
     * a {@link PasspointConfiguration}.  This is used for migrating legacy Passpoint
     * configuration (release N and older).
     *
     * @param wifiConfig The {@link WifiConfiguration} to convert
     * @return {@link PasspointConfiguration}
     */
    public static PasspointConfiguration convertFromWifiConfig(WifiConfiguration wifiConfig) {
        PasspointConfiguration passpointConfig = new PasspointConfiguration();

        // Setup HomeSP.
        HomeSp homeSp = new HomeSp();
        if (TextUtils.isEmpty(wifiConfig.FQDN)) {
            Log.e(TAG, "Missing FQDN");
            return null;
        }
        homeSp.setFqdn(wifiConfig.FQDN);
        homeSp.setFriendlyName(wifiConfig.providerFriendlyName);
        if (wifiConfig.roamingConsortiumIds != null) {
            homeSp.setRoamingConsortiumOis(Arrays.copyOf(
                    wifiConfig.roamingConsortiumIds, wifiConfig.roamingConsortiumIds.length));
        }
        passpointConfig.setHomeSp(homeSp);
        passpointConfig.setCarrierId(wifiConfig.carrierId);

        // Setup Credential.
        Credential credential = new Credential();
        credential.setRealm(wifiConfig.enterpriseConfig.getRealm());
        switch (wifiConfig.enterpriseConfig.getEapMethod()) {
            case WifiEnterpriseConfig.Eap.TTLS:
                credential.setUserCredential(buildUserCredentialFromEnterpriseConfig(
                        wifiConfig.enterpriseConfig));
                break;
            case WifiEnterpriseConfig.Eap.TLS:
                Credential.CertificateCredential certCred = new Credential.CertificateCredential();
                certCred.setCertType(Credential.CertificateCredential.CERT_TYPE_X509V3);
                credential.setCertCredential(certCred);
                break;
            case WifiEnterpriseConfig.Eap.SIM:
                credential.setSimCredential(buildSimCredentialFromEnterpriseConfig(
                        EAPConstants.EAP_SIM, wifiConfig.enterpriseConfig));
                break;
            case WifiEnterpriseConfig.Eap.AKA:
                credential.setSimCredential(buildSimCredentialFromEnterpriseConfig(
                        EAPConstants.EAP_AKA, wifiConfig.enterpriseConfig));
                break;
            case WifiEnterpriseConfig.Eap.AKA_PRIME:
                credential.setSimCredential(buildSimCredentialFromEnterpriseConfig(
                        EAPConstants.EAP_AKA_PRIME, wifiConfig.enterpriseConfig));
                break;
            default:
                Log.e(TAG, "Unsupport EAP method: " + wifiConfig.enterpriseConfig.getEapMethod());
                return null;
        }
        if (credential.getUserCredential() == null && credential.getCertCredential() == null
                && credential.getSimCredential() == null) {
            Log.e(TAG, "Missing credential");
            return null;
        }
        passpointConfig.setCredential(credential);

        return passpointConfig;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof PasspointProvider)) {
            return false;
        }
        PasspointProvider that = (PasspointProvider) thatObject;
        return mProviderId == that.mProviderId
                && (mCaCertificateAliases == null ? that.mCaCertificateAliases == null
                : mCaCertificateAliases.equals(that.mCaCertificateAliases))
                && TextUtils.equals(mClientPrivateKeyAndCertificateAlias,
                that.mClientPrivateKeyAndCertificateAlias)
                && (mConfig == null ? that.mConfig == null : mConfig.equals(that.mConfig))
                && TextUtils.equals(mRemediationCaCertificateAlias,
                that.mRemediationCaCertificateAlias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mProviderId, mCaCertificateAliases,
                mClientPrivateKeyAndCertificateAlias, mConfig, mRemediationCaCertificateAlias);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ProviderId: ").append(mProviderId).append("\n");
        builder.append("CreatorUID: ").append(mCreatorUid).append("\n");
        if (mPackageName != null) {
            builder.append("PackageName: ").append(mPackageName).append("\n");
        }
        builder.append("Configuration Begin ---\n");
        builder.append(mConfig);
        builder.append("Configuration End ---\n");
        builder.append("WifiConfiguration Begin ---\n");
        builder.append(getWifiConfig());
        builder.append("WifiConfiguration End ---\n");
        return builder.toString();
    }

    /**
     * Retrieve the client certificate from the certificates chain.  The certificate
     * with the matching SHA256 digest is the client certificate.
     *
     * @param certChain The client certificates chain
     * @param expectedSha256Fingerprint The expected SHA256 digest of the client certificate
     * @return {@link java.security.cert.X509Certificate}
     */
    private static X509Certificate getClientCertificate(X509Certificate[] certChain,
            byte[] expectedSha256Fingerprint) {
        if (certChain == null) {
            return null;
        }
        try {
            MessageDigest digester = MessageDigest.getInstance("SHA-256");
            for (X509Certificate certificate : certChain) {
                digester.reset();
                byte[] fingerprint = digester.digest(certificate.getEncoded());
                if (Arrays.equals(expectedSha256Fingerprint, fingerprint)) {
                    return certificate;
                }
            }
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            return null;
        }

        return null;
    }

    /**
     * Determines the Passpoint network is a metered network.
     *
     * Expiration date -> non-metered
     * Data limit -> metered
     * Time usage limit -> metered
     * @param passpointConfig instance of {@link PasspointConfiguration}
     * @return {@code true} if the network is a metered network, {@code false} otherwise.
     */
    private boolean isMeteredNetwork(PasspointConfiguration passpointConfig) {
        if (passpointConfig == null) return false;

        // If DataLimit is zero, there is unlimited data usage for the account.
        // If TimeLimit is zero, there is unlimited time usage for the account.
        return passpointConfig.getUsageLimitDataLimit() > 0
                || passpointConfig.getUsageLimitTimeLimitInMinutes() > 0;
    }

    /**
     * Perform a provider match based on the given ANQP elements except for matching 3GPP Network.
     *
     * @param anqpElements List of ANQP elements
     * @param roamingConsortium Roaming Consortium information element from the AP
     * @return {@link PasspointMatch}
     */
    private PasspointMatch matchProviderExceptFor3GPP(
            Map<ANQPElementType, ANQPElement> anqpElements,
            RoamingConsortium roamingConsortium, String matchingSIMImsi) {
        // Domain name matching.
        if (ANQPMatcher.matchDomainName(
                (DomainNameElement) anqpElements.get(ANQPElementType.ANQPDomName),
                mConfig.getHomeSp().getFqdn(), mImsiParameter, matchingSIMImsi)) {
            return PasspointMatch.HomeProvider;
        }

        // ANQP Roaming Consortium OI matching.
        long[] providerOIs = mConfig.getHomeSp().getRoamingConsortiumOis();
        if (ANQPMatcher.matchRoamingConsortium(
                (RoamingConsortiumElement) anqpElements.get(ANQPElementType.ANQPRoamingConsortium),
                providerOIs)) {
            return PasspointMatch.RoamingProvider;
        }

        long[] roamingConsortiums = roamingConsortium.getRoamingConsortiums();
        // Roaming Consortium OI information element matching.
        if (roamingConsortiums != null && providerOIs != null) {
            for (long sta_oi: roamingConsortiums) {
                for (long ap_oi: providerOIs) {
                    if (sta_oi == ap_oi) {
                        return PasspointMatch.RoamingProvider;
                    }
                }
            }
        }

        return PasspointMatch.None;
    }

    /**
     * Fill in WifiEnterpriseConfig with information from an user credential.
     *
     * @param config Instance of {@link WifiEnterpriseConfig}
     * @param credential Instance of {@link UserCredential}
     */
    private void buildEnterpriseConfigForUserCredential(WifiEnterpriseConfig config,
            Credential.UserCredential credential) {
        byte[] pwOctets = Base64.decode(credential.getPassword(), Base64.DEFAULT);
        String decodedPassword = new String(pwOctets, StandardCharsets.UTF_8);
        config.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
        config.setIdentity(credential.getUsername());
        config.setPassword(decodedPassword);
        if (!ArrayUtils.isEmpty(mCaCertificateAliases)) {
            config.setCaCertificateAliases(mCaCertificateAliases.toArray(new String[0]));
        } else {
            config.setCaCertificateAliases(new String[] {SYSTEM_CA_STORE_PATH});
        }
        int phase2Method = WifiEnterpriseConfig.Phase2.NONE;
        switch (credential.getNonEapInnerMethod()) {
            case Credential.UserCredential.AUTH_METHOD_PAP:
                phase2Method = WifiEnterpriseConfig.Phase2.PAP;
                break;
            case Credential.UserCredential.AUTH_METHOD_MSCHAP:
                phase2Method = WifiEnterpriseConfig.Phase2.MSCHAP;
                break;
            case Credential.UserCredential.AUTH_METHOD_MSCHAPV2:
                phase2Method = WifiEnterpriseConfig.Phase2.MSCHAPV2;
                break;
            default:
                // Should never happen since this is already validated when the provider is
                // added.
                Log.wtf(TAG, "Unsupported Auth: " + credential.getNonEapInnerMethod());
                break;
        }
        config.setPhase2Method(phase2Method);
    }

    /**
     * Fill in WifiEnterpriseConfig with information from a certificate credential.
     *
     * @param config Instance of {@link WifiEnterpriseConfig}
     */
    private void buildEnterpriseConfigForCertCredential(WifiEnterpriseConfig config) {
        config.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        config.setClientCertificateAlias(mClientPrivateKeyAndCertificateAlias);
        if (!ArrayUtils.isEmpty(mCaCertificateAliases)) {
            config.setCaCertificateAliases(mCaCertificateAliases.toArray(new String[0]));
        } else {
            config.setCaCertificateAliases(new String[] {SYSTEM_CA_STORE_PATH});
        }
    }

    /**
     * Fill in WifiEnterpriseConfig with information from a SIM credential.
     *
     * @param config Instance of {@link WifiEnterpriseConfig}
     * @param credential Instance of {@link SimCredential}
     */
    private void buildEnterpriseConfigForSimCredential(WifiEnterpriseConfig config,
            Credential.SimCredential credential) {
        int eapMethod = WifiEnterpriseConfig.Eap.NONE;
        switch(credential.getEapType()) {
            case EAPConstants.EAP_SIM:
                eapMethod = WifiEnterpriseConfig.Eap.SIM;
                break;
            case EAPConstants.EAP_AKA:
                eapMethod = WifiEnterpriseConfig.Eap.AKA;
                break;
            case EAPConstants.EAP_AKA_PRIME:
                eapMethod = WifiEnterpriseConfig.Eap.AKA_PRIME;
                break;
            default:
                // Should never happen since this is already validated when the provider is
                // added.
                Log.wtf(TAG, "Unsupported EAP Method: " + credential.getEapType());
                break;
        }
        config.setEapMethod(eapMethod);
        config.setPlmn(credential.getImsi());
    }

    private static void setAnonymousIdentityToNaiRealm(WifiEnterpriseConfig config, String realm) {
        /**
         * Set WPA supplicant's anonymous identity field to a string containing the NAI realm, so
         * that this value will be sent to the EAP server as part of the EAP-Response/ Identity
         * packet. WPA supplicant will reset this field after using it for the EAP-Response/Identity
         * packet, and revert to using the (real) identity field for subsequent transactions that
         * request an identity (e.g. in EAP-TTLS).
         *
         * This NAI realm value (the portion of the identity after the '@') is used to tell the
         * AAA server which AAA/H to forward packets to. The hardcoded username, "anonymous", is a
         * placeholder that is not used--it is set to this value by convention. See Section 5.1 of
         * RFC3748 for more details.
         *
         * NOTE: we do not set this value for EAP-SIM/AKA/AKA', since the EAP server expects the
         * EAP-Response/Identity packet to contain an actual, IMSI-based identity, in order to
         * identify the device.
         */
        config.setAnonymousIdentity("anonymous@" + realm);
    }

    /**
     * Helper function for creating a
     * {@link android.net.wifi.hotspot2.pps.Credential.UserCredential} from the given
     * {@link WifiEnterpriseConfig}
     *
     * @param config The enterprise configuration containing the credential
     * @return {@link android.net.wifi.hotspot2.pps.Credential.UserCredential}
     */
    private static Credential.UserCredential buildUserCredentialFromEnterpriseConfig(
            WifiEnterpriseConfig config) {
        Credential.UserCredential userCredential = new Credential.UserCredential();
        userCredential.setEapType(EAPConstants.EAP_TTLS);

        if (TextUtils.isEmpty(config.getIdentity())) {
            Log.e(TAG, "Missing username for user credential");
            return null;
        }
        userCredential.setUsername(config.getIdentity());

        if (TextUtils.isEmpty(config.getPassword())) {
            Log.e(TAG, "Missing password for user credential");
            return null;
        }
        String encodedPassword =
                new String(Base64.encode(config.getPassword().getBytes(StandardCharsets.UTF_8),
                        Base64.DEFAULT), StandardCharsets.UTF_8);
        userCredential.setPassword(encodedPassword);

        switch(config.getPhase2Method()) {
            case WifiEnterpriseConfig.Phase2.PAP:
                userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_PAP);
                break;
            case WifiEnterpriseConfig.Phase2.MSCHAP:
                userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_MSCHAP);
                break;
            case WifiEnterpriseConfig.Phase2.MSCHAPV2:
                userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_MSCHAPV2);
                break;
            default:
                Log.e(TAG, "Unsupported phase2 method for TTLS: " + config.getPhase2Method());
                return null;
        }
        return userCredential;
    }

    /**
     * Helper function for creating a
     * {@link android.net.wifi.hotspot2.pps.Credential.SimCredential} from the given
     * {@link WifiEnterpriseConfig}
     *
     * @param eapType The EAP type of the SIM credential
     * @param config The enterprise configuration containing the credential
     * @return {@link android.net.wifi.hotspot2.pps.Credential.SimCredential}
     */
    private static Credential.SimCredential buildSimCredentialFromEnterpriseConfig(
            int eapType, WifiEnterpriseConfig config) {
        Credential.SimCredential simCredential = new Credential.SimCredential();
        if (TextUtils.isEmpty(config.getPlmn())) {
            Log.e(TAG, "Missing IMSI for SIM credential");
            return null;
        }
        simCredential.setImsi(config.getPlmn());
        simCredential.setEapType(eapType);
        return simCredential;
    }
}
