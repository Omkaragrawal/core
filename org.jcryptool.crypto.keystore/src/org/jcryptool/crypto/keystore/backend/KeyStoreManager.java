// -----BEGIN DISCLAIMER-----
/*******************************************************************************
 * Copyright (c) 2013 JCrypTool Team and Contributors
 *
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
// -----END DISCLAIMER-----
package org.jcryptool.crypto.keystore.backend;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import javax.crypto.SecretKey;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.filesystem.URIUtil;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.PlatformUI;
import org.jcryptool.core.logging.dialogs.JCTMessageDialog;
import org.jcryptool.core.logging.utils.LogUtil;
import org.jcryptool.core.operations.providers.ProviderManager2;
import org.jcryptool.core.util.directories.DirectoryService;
import org.jcryptool.core.util.ui.PasswordPrompt;
import org.jcryptool.crypto.keys.IKeyStoreAlias;
import org.jcryptool.crypto.keys.KeyType;
import org.jcryptool.crypto.keystore.KeyStorePlugin;
import org.jcryptool.crypto.keystore.exceptions.NoKeyStoreFileException;
import org.jcryptool.crypto.keystore.ui.views.nodes.ContactManager;

import de.flexiprovider.api.keys.Key;

/**
 * <p>
 * This class represents the JCrypTool keystore. It is implemented as a singleton and can be used by any plug-in by
 * calling the <code>getInstance</code> method. Most methods require a <code>KeyStoreAlias</code> as method parameter to
 * access the keystores content.
 * </p>
 * 
 * <p>
 * The default password for all protected entries is <b>1234</b>. In case your plug-in offers access to a protected
 * entry, it is recommended to show this password somewhere in your GUI.
 * </p>
 * 
 * <p>
 * The default keystore password is <b>jcryptool</b>. Users do not require this password.
 * </p>
 * 
 * @see org.jcryptool.crypto.keystore.backend.KeyStoreAlias
 * 
 * @author Tobias Kern, Dominik Schadow
 */
public class KeyStoreManager {
    /** Hard-coded standard password for the platform keystore. */
    private static final char[] KEYSTORE_PASSWORD = { 'j', 'c', 'r', 'y', 'p', 't', 'o', 'o', 'l' };
    /** Hard-coded standard password for the keys. */
    private static final char[] KEY_PASSWORD = { '1', '2', '3', '4' };
    /** The JCrypTool keystore of type JCEKS. */
    private KeyStore keyStore = null;
    /** The IFileStore representing the JCrypTool keystore. */
    private IFileStore keyStoreFileStore = null;
    /** The JCrypTool keystore instance, only one instance exists. */
    private static KeyStoreManager instance;

    /**
     * The JCrypTool keystore is implemented as a singleton, therefore the constructor is private. Use
     * <code>getInstance()</code> to retrieve the active instance instead.
     * 
     * @see org.jcryptool.crypto.keystore.backend.KeyStoreManager#getInstance()
     */
    private KeyStoreManager() {
        ProviderManager2.getInstance();
        try {
            this.keyStore = KeyStore.getInstance("JCEKS"); //$NON-NLS-1$
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID,
                    "KeyStoreException while accessing an instance of JCEKS keystore", e, true);
        }
        KeyStorePlugin.loadPreferences();
        try {
            loadKeyStore(KeyStorePlugin.getCurrentKeyStoreURI());
        } catch (NoKeyStoreFileException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "Unable to load keystore", e, true);
        }
    }

    /**
     * <p>
     * Returns the fully initialized instance of the JCrypTool keystore. The returned instance is never null and always
     * fully initialized.
     * </p>
     * 
     * <p>
     * Use <code>KeyStoreManager ksm = KeyStoreManager.getInstance()</code> to retrieve the instance.
     * </p>
     * 
     * @return The JCrypTool keystore instance
     */
    public synchronized static KeyStoreManager getInstance() {
        if (instance == null) {
            instance = new KeyStoreManager();
        }

        return instance;
    }

    public void createNewKeyStore(URI uri) {
        try {
            KeyStore newKeyStore = KeyStore.getInstance("JCEKS"); //$NON-NLS-1$
            IFileStore store = EFS.getStore(uri);
            newKeyStore.load(null, KEYSTORE_PASSWORD);
            newKeyStore.store(store.openOutputStream(EFS.APPEND, null), KEYSTORE_PASSWORD);
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while creating a new keystore", e, true);
        } catch (CoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "CoreException while creating a new keystore", e, true);
        } catch (NoSuchAlgorithmException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "NoSuchAlgorithmException while creating a new keystore", e,
                    true);
        } catch (CertificateException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "CertificateException while creating a new keystore", e, true);
        } catch (IOException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "IOException while creating a new keystore", e, true);
        }
    }

    public void deleteContact(String contactName) {
        Enumeration<String> en;
        try {
            en = this.getAliases();
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while accessing the aliases", e, true);
            return;
        }
        KeyStoreAlias current = null;
        while (en != null && en.hasMoreElements()) {
            current = new KeyStoreAlias(en.nextElement());
            if (current.getContactName().equals(contactName)) {
                try {
                    this.keyStore.deleteEntry(current.getAliasString());
                } catch (KeyStoreException e) {
                    LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while trying to delete an entry", e,
                            true);
                }
            }
        }
        this.storeKeyStore();
        ContactManager.getInstance().removeContact(contactName);
    }

    public void delete(KeyStoreAlias alias) {
        try {
            if (alias.getKeyStoreEntryType().equals(KeyType.KEYPAIR_PRIVATE_KEY)) {
                // if it is a private key, make sure that the corresponding
                // public key gets deleted as well
                KeyStoreAlias pub = this.getPublicForPrivate(alias);
                if (pub != null) {
                    this.keyStore.deleteEntry(pub.getAliasString());
                }
            } else if (alias.getKeyStoreEntryType().equals(KeyType.KEYPAIR_PUBLIC_KEY)) {
                KeyStoreAlias priv = this.getPrivateForPublic(alias);
                if (priv != null) {
                    this.keyStore.deleteEntry(priv.getAliasString());
                }
            }
            this.keyStore.deleteEntry(alias.getAliasString());
            this.storeKeyStore();
            ContactManager.getInstance().removeEntry(alias);
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while deleting an entry", e, true);
        }
    }

    private KeyStoreAlias getPublicForPrivate(KeyStoreAlias privateAlias) {
        try {
            Enumeration<String> en = this.getAliases();
            KeyStoreAlias tmp;
            while (en != null && en.hasMoreElements()) {
                tmp = new KeyStoreAlias(en.nextElement());
                if (privateAlias.getHashValue().toLowerCase().equals(tmp.getHashValue().toLowerCase())
                        && tmp.getKeyStoreEntryType() == KeyType.KEYPAIR_PUBLIC_KEY) {
                    return tmp;
                }
            }
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while accessing the aliases", e, true);
        }
        return null;
    }

    /**
     * extracts the private alias matching the known {@link #publicAlias}
     * 
     * @return the private alias or <code>null</code> if none is found or there was a problem accessing the keystore
     */
    public KeyStoreAlias getPrivateForPublic(KeyStoreAlias publicAlias) {
        if (publicAlias == null)
            return null;

        Enumeration<String> aliases;
        try {
            aliases = this.getAliases();
        } catch (KeyStoreException e) {
            LogUtil.logError(e);
            return null;
        }
        KeyStoreAlias alias;
        while (aliases != null && aliases.hasMoreElements()) {
            alias = new KeyStoreAlias(aliases.nextElement());
            if (alias.getHashValue().equalsIgnoreCase(publicAlias.getHashValue()) && !alias.equals(publicAlias)
                    && alias.getKeyStoreEntryType() == KeyType.KEYPAIR_PRIVATE_KEY) {
                return alias;
            }
        }
        return null;
    }

    public void addCertificate(Certificate certificate, KeyStoreAlias alias) {
        try {
            this.keyStore.setEntry(alias.getAliasString(), new KeyStore.TrustedCertificateEntry(certificate), null);
            this.storeKeyStore();
            ContactManager.getInstance().addCertificate(alias);
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while adding a certificate", e, true);
        }
    }

    public void addSecretKey(SecretKey key, String password, KeyStoreAlias alias) {
        try {
            this.keyStore.setEntry(alias.getAliasString(), new KeyStore.SecretKeyEntry(key),
                    new KeyStore.PasswordProtection(password.toCharArray()));
            this.storeKeyStore();
            ContactManager.getInstance().addSecretKey(alias);
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while adding a secret key", e, true);
        }
    }

    public void addKeyPair(PrivateKey privateKey, Certificate publicKey, String password, KeyStoreAlias privateAlias,
            KeyStoreAlias publicAlias) {
        Certificate[] certs = new Certificate[1];
        certs[0] = publicKey;
        try {
            this.keyStore.setEntry(privateAlias.getAliasString(), new KeyStore.PrivateKeyEntry(privateKey, certs),
                    new KeyStore.PasswordProtection(password.toCharArray()));
            this.keyStore.setEntry(publicAlias.getAliasString(), new KeyStore.TrustedCertificateEntry(publicKey), null);
            this.storeKeyStore();
            ContactManager.getInstance().addKeyPair(privateAlias, publicAlias);
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while adding a key pair", e, true);
        }
    }

    /**
     * Updates the private key in a key pair. Necessary for some one-time signature algorithms
     */
    public void updateKeyPair(PrivateKey privateKey, char[] password, KeyStoreAlias privateAlias) {
        // Check that private key is in the keystore and that we don't change the password
        try {
            getPrivateKey(privateAlias, password);
        } catch (Exception e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while updating a private key", e, true);
        }

        try {
            KeyStoreAlias publicAlias = getPublicForPrivate(privateAlias);
            Certificate publicKey = getPublicKey(publicAlias);
            Certificate[] certs = new Certificate[1];
            certs[0] = publicKey;

            keyStore.setEntry(privateAlias.getAliasString(), new KeyStore.PrivateKeyEntry(privateKey, certs),
                    new KeyStore.PasswordProtection(password));
            storeKeyStore();
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while updating a priavte key", e, true);
        }
    }

    public Certificate getPublicKey(IKeyStoreAlias alias) {
        try {
            KeyStore.TrustedCertificateEntry entry = (KeyStore.TrustedCertificateEntry) this.keyStore.getEntry(
                    alias.getAliasString(), null);
            return entry.getTrustedCertificate();
        } catch (NoSuchAlgorithmException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "NoSuchAlgorithmException while accessing a public key", e, true);
        } catch (UnrecoverableEntryException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "UnrecoverableEntryException while accessing a public key", e,
                    false);
            MessageDialog.openInformation(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
                    Messages.getString("Label.ProblemOccured"), //$NON-NLS-1$
                    Messages.getString("Label.KeyNotAccessable")); //$NON-NLS-1$
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while accessing a public key", e, true);
        }
        return null;
    }

    public PrivateKey getPrivateKey(IKeyStoreAlias alias, char[] password) throws Exception {
        try {
            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) this.keyStore.getEntry(alias.getAliasString(),
                    new KeyStore.PasswordProtection(password));
            return entry.getPrivateKey();
        } catch (NoSuchAlgorithmException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "NoSuchAlgorithmException while accessing a private key", e,
                    true);
        } catch (UnrecoverableEntryException e) {
            throw e;
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while accessing a private key", e, true);
        }

        return null;
    }

    /**
     * tries to retrieve the key from keystore using the default password if the operation succeeds, the default
     * password will be updated, if it fails, the user have to enter a password into a prompt window
     * 
     * @param alias
     * @return
     */
    public Key getKey(IKeyStoreAlias alias) {

        try {
            Key key = KeyStoreManager.getInstance().getKey(alias, KEY_PASSWORD);
            return key;
        } catch (Exception e) {
            // prompt for password and try again
            char[] prompt_password = PasswordPrompt.prompt(PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                    .getShell());
            if (prompt_password == null) {
                JCTMessageDialog.showInfoDialog(new Status(IStatus.INFO, KeyStorePlugin.PLUGIN_ID, Messages
                        .getString("ExAccessKeystorePassword"), e)); //$NON-NLS-1$
                return null;
            }
            try {
                Key key = KeyStoreManager.getInstance().getKey(alias, prompt_password);
                return key;
            } catch (UnrecoverableEntryException ex) {
                JCTMessageDialog.showInfoDialog(new Status(IStatus.INFO, KeyStorePlugin.PLUGIN_ID, Messages
                        .getString("ExAccessKeystorePassword"), e)); //$NON-NLS-1$
                return null;
            } catch (Exception ex) {
                LogUtil.logError(KeyStorePlugin.PLUGIN_ID, Messages.getString("ExAccessKeystorePassword"), e, true);
                return null;
            }
        }
    }

    public ArrayList<KeyStoreAlias> getAllPublicKeys() {
        ArrayList<KeyStoreAlias> publicKeys = new ArrayList<KeyStoreAlias>();

        try {
            Enumeration<String> aliases = keyStore.aliases();

            while (aliases.hasMoreElements()) {
                KeyStoreAlias localKeyStoreAlias = new KeyStoreAlias(aliases.nextElement());
                if (localKeyStoreAlias.getKeyStoreEntryType().getType().contains(KeyType.KEYPAIR.getType())) {
                    if (localKeyStoreAlias.getKeyStoreEntryType().equals(KeyType.KEYPAIR_PUBLIC_KEY)) {
                        publicKeys.add(localKeyStoreAlias);
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.logError(e);
        }

        return publicKeys;
    }

    public ArrayList<KeyStoreAlias> getAllPrivateKeys() {
        ArrayList<KeyStoreAlias> privateKeys = new ArrayList<KeyStoreAlias>();

        try {
            Enumeration<String> aliases = keyStore.aliases();

            while (aliases.hasMoreElements()) {
                KeyStoreAlias localKeyStoreAlias = new KeyStoreAlias(aliases.nextElement());
                if (localKeyStoreAlias.getKeyStoreEntryType().getType().contains(KeyType.KEYPAIR.getType())) { // asymmetric
                    if (localKeyStoreAlias.getKeyStoreEntryType().equals(KeyType.KEYPAIR_PRIVATE_KEY)) {
                        privateKeys.add(localKeyStoreAlias);
                    }
                }
            }
        } catch (Exception e) {
            LogUtil.logError(e);
        }

        return privateKeys;
    }

    /**
     * let the function decide which key type the alias is associated with
     * 
     * @param alias
     * @param password
     * @return key from the keystore
     * @throws Exception
     */
    public Key getKey(IKeyStoreAlias alias, char[] password) throws Exception {
        switch (alias.getKeyStoreEntryType()) {
        case SECRETKEY:
            return (Key) KeyStoreManager.getInstance().getSecretKey(alias, password);
        case KEYPAIR_PRIVATE_KEY:
            return (Key) KeyStoreManager.getInstance().getPrivateKey(alias, password);
        case KEYPAIR_PUBLIC_KEY:
            Certificate cert = KeyStoreManager.getInstance().getPublicKey(alias);
            if (cert == null)
                return null;
            return (Key) cert.getPublicKey();
        case PUBLICKEY:
            Certificate certpub = KeyStoreManager.getInstance().getPublicKey(alias);
            if (certpub == null)
                return null;
            return (Key) certpub.getPublicKey();
        default:
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID,
                    Messages.getString("ExKeyTypeUnsupported") + alias.getKeyStoreEntryType(), null, true);
            return null;
        }
    }

    public Certificate[] getCertificateChain(KeyStoreAlias alias, char[] password) {
        try {
            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) this.keyStore.getEntry(alias.getAliasString(),
                    new KeyStore.PasswordProtection(password));
            return entry.getCertificateChain();
        } catch (NoSuchAlgorithmException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "NoSuchAlgorithmException while accessing a private key", e,
                    true);
        } catch (UnrecoverableEntryException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "UnrecoverableEntryException while accessing a private key", e,
                    true);
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while accessing a private key", e, true);
        }

        return null;
    }

    public Certificate getCertificate(KeyStoreAlias alias) {
        try {
            return this.keyStore.getCertificate(alias.getAliasString());
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while accessing certificate", e, true);
        }

        return null;
    }

    public SecretKey getSecretKey(IKeyStoreAlias alias, char[] password) throws Exception {
        try {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) this.keyStore.getEntry(alias.getAliasString(),
                    new KeyStore.PasswordProtection(password));
            return entry.getSecretKey();
        } catch (NoSuchAlgorithmException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "NoSuchAlgorithmException while accessing a secret key", e, true);
        } catch (UnrecoverableEntryException e) {
            throw e;
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while accessing a secret key", e, true);
        }
        return null;
    }

    public Enumeration<String> getAliases() throws KeyStoreException {
        return this.keyStore.aliases();
    }

    public void loadKeyStore(URI currentKeyStoreURI) throws NoKeyStoreFileException {
        if (currentKeyStoreURI != null) {
            try {
                if (currentKeyStoreURI.toString().endsWith(";")) {
                    String temp = currentKeyStoreURI.toString();
                    try {
                        currentKeyStoreURI = new URI(temp.substring(0, temp.length() - 1));
                    } catch (URISyntaxException ex) {
                        LogUtil.logError(KeyStorePlugin.PLUGIN_ID,
                                "The keystore URI contains a trailing ;, but cut off failed", ex, false);
                    }
                }
                this.keyStoreFileStore = EFS.getStore(currentKeyStoreURI);
                this.load(this.keyStoreFileStore, KEYSTORE_PASSWORD);
            } catch (CoreException e) {
                LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "CoreException while accessing the keystore file store.", e,
                        false);
                throw new NoKeyStoreFileException(new Status(IStatus.WARNING,
                        "org.jcryptool.crypto.keystore", "No keystore file with the given name exists!")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        } else {
            // load and establish default keystore
            LogUtil.logInfo("uri does not exist."); //$NON-NLS-1$
            try {
                this.keyStoreFileStore = EFS.getStore(KeyStorePlugin.getPlatformKeyStoreURI());
                LogUtil.logInfo("PlatformKS: " + KeyStorePlugin.getPlatformKeyStore()); //$NON-NLS-1$
                KeyStorePlugin.setCurrentKeyStore(KeyStorePlugin.getPlatformKeyStoreName());
                if (KeyStorePlugin.getAvailableKeyStores().isEmpty()) {
                    List<String> newList = new ArrayList<String>(1);
                    newList.add(KeyStorePlugin.getPlatformKeyStore());
                    KeyStorePlugin.setAvailableKeyStores(newList);
                }
                KeyStorePlugin.savePreferences();
                this.createNewPlatformKeyStore();
            } catch (CoreException e) {
                LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "CoreException", e, true);
            }
        }
    }

    /**
     * Loads a keystore from the filesystem.
     * 
     * @param fullyQualifiedName The full name of the keystore file, including path information
     * @param password The password with which the keystore is protected
     */
    private void load(IFileStore keyStoreFileStore, char[] password) {
        InputStream is = null;
        try {
            is = new BufferedInputStream(keyStoreFileStore.openInputStream(EFS.NONE, null));
            this.keyStore.load(is, password);
        } catch (CoreException e) {
            if (URIUtil.equals(keyStoreFileStore.toURI(), KeyStorePlugin.getPlatformKeyStoreURI())) {
                this.createNewPlatformKeyStore();
            } else {
                LogUtil.logError(KeyStorePlugin.PLUGIN_ID,
                        "CoreException while opening an input stream on a file store", e, true);
            }
        } catch (NoSuchAlgorithmException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "NoSuchAlgorihtmException while loading a keystore", e, true);
        } catch (CertificateException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "CertificateException while loading a keystore", e, true);
        } catch (IOException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "IOException while loading a keystore", e, true);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "Failed to close BufferdInputStream", e, true);
                }
            }
        }
    }

    private void createNewPlatformKeyStore() {
        try {
            this.keyStore.load(null, KEYSTORE_PASSWORD);
        } catch (NoSuchAlgorithmException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "NoSuchAlgorithmException", e, true);
        } catch (CertificateException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "CertificateException", e, true);
        } catch (IOException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "IOException", e, true);
        }

        BufferedInputStream is = null;

        try {
            File flexiProvider = new File(DirectoryService.getWorkspaceDir(), KeyStorePlugin.getFlexiProviderFolder());

            if (!flexiProvider.exists()) {
                flexiProvider.mkdir();
            }

            URL url = KeyStorePlugin.getDefault().getBundle().getEntry("/"); //$NON-NLS-1$
            File file = new File(FileLocator.toFileURL(url).getFile() + "keystore" + File.separatorChar //$NON-NLS-1$
                    + "jctKeystore.ksf"); //$NON-NLS-1$
            IFileStore jctKeystore = EFS.getStore(file.toURI());
            jctKeystore.copy(EFS.getStore(KeyStorePlugin.getPlatformKeyStoreURI()), EFS.NONE, null);

            is = new BufferedInputStream(this.keyStoreFileStore.openInputStream(EFS.NONE, null));
            this.keyStore.load(is, KEYSTORE_PASSWORD);
        } catch (Exception e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "Exception", e, false);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "Failed to close BufferdInputStream", e, false);
                }
            }
        }
    }

    public void storeKeyStore() {
        OutputStream os;
        try {
            os = new BufferedOutputStream(this.keyStoreFileStore.openOutputStream(EFS.NONE, null));
            this.keyStore.store(os, KEYSTORE_PASSWORD);
            os.close();
        } catch (CoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "CoreException while storing a keystore", e, true);
        } catch (IOException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "IOException while storing a keystore", e, false);
        } catch (KeyStoreException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "KeyStoreException while storing a keystore", e, true);
        } catch (NoSuchAlgorithmException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "NoSuchAlgorithmException while storing a keystore", e, true);
        } catch (CertificateException e) {
            LogUtil.logError(KeyStorePlugin.PLUGIN_ID, "CertificateException while storing a keystore", e, true);
        }
    }

    public static char[] getDefaultKeyPassword() {
        return KEY_PASSWORD;
    }
}
