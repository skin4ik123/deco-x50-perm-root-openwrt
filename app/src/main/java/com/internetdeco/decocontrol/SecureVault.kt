package com.internetdeco.decocontrol
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
class SecureVault(private val context: Context) {
 private val preferences=context.getSharedPreferences("vault",0)
 @Volatile private var cachedKey:SecretKey?=null
 private val encrypted=EncryptedVault({preferences.getString("sealed",null)},
  {preferences.edit().putString("sealed",it).commit()},::key)
 private fun key(): SecretKey {
  cachedKey?.let{return it}
  synchronized(KEY_LOCK){
  cachedKey?.let{return it}
  val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  val alias="decocontrol.v1.vault"
  if(store.containsAlias(alias)) return (store.getKey(alias,null) as SecretKey).also{cachedKey=it}
  check(!preferences.contains("sealed")){"Saved credentials cannot be opened. Unlock your phone and try again."}
  return KeyGenerator.getInstance("AES","AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())}.generateKey()
   .also{cachedKey=it}
  }
 }
 fun read():JSONObject=encrypted.read()
 fun update(block:(JSONObject)->Unit)=encrypted.update(block)
 fun get(key:String)=read().optString(key,"")
 fun put(key:String,value:String)=update{it.put(key,value)}
 private companion object {val KEY_LOCK=Any()}
}
