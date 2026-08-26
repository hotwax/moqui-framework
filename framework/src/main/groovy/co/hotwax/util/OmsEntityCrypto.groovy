package co.hotwax.util

import groovy.transform.CompileStatic
import org.apache.shiro.crypto.cipher.AesCipherService
import org.apache.shiro.crypto.cipher.OperationMode
import org.apache.shiro.crypto.cipher.PaddingScheme
import org.apache.shiro.crypto.hash.DefaultHashService
import org.apache.shiro.crypto.hash.HashRequest
import org.apache.shiro.crypto.hash.HashService;

import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.security.Key;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@CompileStatic
public final class OmsEntityCrypto {

    private static final ConcurrentMap<String, byte[]> keyMap = new ConcurrentHashMap<>();
    protected final static Logger logger = LoggerFactory.getLogger(OmsEntityCrypto.class);
    //private static ShiroStorageHandler storageHandler;

    // Private constructor to prevent instantiation
    private OmsEntityCrypto() {
    }

    /** Encrypts an Object into an encrypted String */
    public static String encrypt(ExecutionContext ec, String keyName, Object obj) throws Exception {
        return encrypt(ec, keyName, EncryptMethod.SALT, obj);
    }

    /** Encrypts an Object into an encrypted String with specified method */
    public static String encrypt(ExecutionContext ec, String keyName, EncryptMethod encryptMethod, Object obj) throws Exception {
        byte[] key = findOrCreateKey(ec, keyName);
        byte[] data = null;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
            data = bos.toByteArray();
        } catch (IOException e) {
            logger.error(e.getMessage());
        }
        return ShiroStorageHandler.getInstance().encryptValue(encryptMethod, key, data);
    }

    /** Decrypts an encrypted String into an Object */
    public static Object decrypt(ExecutionContext ec, String keyName, EncryptMethod encryptMethod, String encryptedString) throws Exception {
        byte[] key = findOrCreateKey(ec, keyName);

        byte[] decryptedBytes = ShiroStorageHandler.getInstance().decryptValue(key, encryptMethod, encryptedString);
        try (ByteArrayInputStream bis = new ByteArrayInputStream(decryptedBytes);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return ois.readObject();

        }
        //return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    /** Finds or creates a key */
    private static byte[] findOrCreateKey(ExecutionContext ec, String keyName) throws Exception {
        String hashedKeyName = ShiroStorageHandler.getInstance().getHashedKeyName(keyName);
        String keyMapName = ShiroStorageHandler.getInstance().getKeyMapPrefix(hashedKeyName) + hashedKeyName;
        if (keyMap.containsKey(keyMapName)) {
            return keyMap.get(keyMapName);
        }

        EntityFacade ef = ec.getEntity();
        EntityValue keyValue = ef.find("EntityKeyStore").condition("keyName", hashedKeyName).useCache(true).one();
        if (keyValue != null) {
            //byte[] keyBytes = Base64.getDecoder().decode((String) keyValue.get("keyText"));
            byte[] keyBytes = ShiroStorageHandler.getInstance().decodeKeyBytes(keyValue.getString("keyText"));
            keyMap.putIfAbsent(keyMapName, keyBytes);
            return keyBytes;
        }

        // Generate a new key if not found
        Key key = ShiroStorageHandler.getInstance().generateNewKey();
        String encodedKey = ShiroStorageHandler.getInstance().encodeKey(key.getEncoded());

        // Store the new key in EntityKeyStore
        EntityValue newKeyValue = ef.makeValue("EntityKeyStore");
        newKeyValue.set("keyName", hashedKeyName);
        newKeyValue.set("keyText", encodedKey);
        newKeyValue.create();

        byte[] keyBytes = key.getEncoded();
        keyMap.putIfAbsent(keyMapName, keyBytes);
        return keyBytes;
    }

    /** Static inner class for Shiro-based encryption handling */
    private static class ShiroStorageHandler {

        private static ShiroStorageHandler instance;
        private final HashService hashService;
        private final AesCipherService cipherService;
        private final AesCipherService saltedCipherService;
        private final byte[] kek; // Optional Key Encryption Key (KEK)

        // Private constructor for singleton
        private ShiroStorageHandler() {
            this.hashService = new DefaultHashService();
            this.cipherService = new AesCipherService();
            this.cipherService.setMode(OperationMode.ECB);
            this.cipherService.setPaddingScheme(PaddingScheme.PKCS5);
            this.saltedCipherService = new AesCipherService();
            //We are not using encryption key in ofbiz, so keeping this null. If any one configured the encryption key, need to use the key here as well
            this.kek = null;
        }

        // Singleton instance retrieval method
        public static ShiroStorageHandler getInstance() {
            if (instance == null) {
                synchronized (ShiroStorageHandler.class) {
                    if (instance == null) {
                        instance = new ShiroStorageHandler();
                    }
                }
            }
            return instance;
        }

        /** Generate a new AES key */
        public Key generateNewKey() {
            return saltedCipherService.generateNewKey(); // Default key size of 128 bits
        }

        /** Generate a hashed key name */
        public String getHashedKeyName(String originalKeyName) {
            HashRequest hashRequest = new HashRequest.Builder().setSource(originalKeyName).build();
            return hashService.computeHash(hashRequest).toBase64();
        }

        /** Get key map prefix */
        public String getKeyMapPrefix(String hashedKeyName) {
            return "{shiro}";
        }

        /** Decode key bytes */
        public byte[] decodeKeyBytes(String keyText) {
            byte[] keyBytes = Base64.getDecoder().decode(keyText);
            if (kek != null) {
                keyBytes = saltedCipherService.decrypt(keyBytes, kek).getClonedBytes();
            }
            return keyBytes;
        }

        /** Encode key bytes */
        public String encodeKey(byte[] key) {
            if (kek != null) {
                return saltedCipherService.encrypt(key, kek).toBase64();
            } else {
                return Base64.getEncoder().encodeToString(key);
            }
        }

        /** Decrypt a value using the provided key and encryption method */
        public byte[] decryptValue(byte[] key, EncryptMethod encryptMethod, String encryptedString) {
            switch (encryptMethod) {
                case EncryptMethod.SALT:
                    return saltedCipherService.decrypt(Base64.getDecoder().decode(encryptedString), key).getClonedBytes();
                default:
                    return cipherService.decrypt(Base64.getDecoder().decode(encryptedString), key).getClonedBytes();
            }
        }

        /** Encrypt a value using the provided key and encryption method */
        public String encryptValue(EncryptMethod encryptMethod, byte[] key, byte[] objBytes) {
            switch (encryptMethod) {
                case EncryptMethod.SALT:
                    return saltedCipherService.encrypt(objBytes, key).toBase64();
                default:
                    return cipherService.encrypt(objBytes, key).toBase64();
            }
        }
    }
    // Enumeration for the encryption method (you can expand it as needed)
    public enum EncryptMethod {
        FALSE, TRUE, SALT;

        public boolean isEncrypted() {
            return this != FALSE;
        }
    }

}
