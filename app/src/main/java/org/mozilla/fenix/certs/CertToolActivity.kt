/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.certs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import kotlin.concurrent.thread

/**
 * Certificate tool, opened from Settings. Generates signing certificates
 * locally on the device — nothing is uploaded anywhere.
 *
 * Types: JKS (.jks), PKCS12 (.p12), PEM certificate + private key,
 * PEM certificate only. Key: RSA 2048 / RSA 4096 / EC P-256 / EC P-384.
 * "一键生成并下载" generates with the filled-in values and opens the
 * system save dialog; the file is written only where the user chooses.
 */
class CertToolActivity : AppCompatActivity() {

    private lateinit var typeSpinner: Spinner
    private lateinit var keySpinner: Spinner
    private lateinit var aliasInput: EditText
    private lateinit var cnInput: EditText
    private lateinit var orgInput: EditText
    private lateinit var yearsInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var resultView: TextView

    private var pendingBytes: ByteArray? = null
    private var pendingFileName: String = "certificate.p12"
    private var pendingMime: String = "application/x-pkcs12"

    private val createDocumentRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri == null) {
            Toast.makeText(this, "已取消保存", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val bytes = pendingBytes ?: return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(this, "已保存：$pendingFileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Security.addProvider(BouncyCastleProvider())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "证书工具"
            textSize = 20f
            setPadding(0, 0, 0, dp(12))
        })
        root.addView(TextView(this).apply {
            text = "在手机本地生成签名证书，不上传任何信息。生成后选择保存位置下载。"
            setPadding(0, 0, 0, dp(12))
        })

        typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CertToolActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("PKCS12 (.p12)", "JKS (.jks)", "PEM 证书+私钥 (.crt + .key)", "PEM 仅证书 (.crt)"),
            )
        }
        keySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CertToolActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("RSA 2048", "RSA 4096", "EC P-256", "EC P-384"),
            )
        }
        aliasInput = EditText(this).apply { hint = "别名 (alias)"; setText("iceraven") }
        cnInput = EditText(this).apply { hint = "通用名 CN"; setText("Iceraven") }
        orgInput = EditText(this).apply { hint = "组织 O（可空）"; setText("22085") }
        yearsInput = EditText(this).apply { hint = "有效期（年）"; setText("10"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        passwordInput = EditText(this).apply {
            hint = "密码（留空自动生成）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        resultView = TextView(this).apply { setPadding(0, dp(12), 0, 0) }

        root.addView(label("证书类型"))
        root.addView(typeSpinner)
        root.addView(label("密钥算法"))
        root.addView(keySpinner)
        root.addView(label("别名"))
        root.addView(aliasInput)
        root.addView(label("通用名 CN"))
        root.addView(cnInput)
        root.addView(label("组织 O"))
        root.addView(orgInput)
        root.addView(label("有效期（年）"))
        root.addView(yearsInput)
        root.addView(label("密码"))
        root.addView(passwordInput)

        val generateButton = Button(this).apply {
            text = "一键生成并下载"
            setOnClickListener { generateAndDownload() }
        }
        val previewButton = Button(this).apply {
            text = "只生成并查看指纹"
            setOnClickListener { generatePreviewOnly() }
        }
        root.addView(generateButton)
        root.addView(previewButton)
        root.addView(resultView)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    private fun generateAndDownload() {
        setBusy(true)
        thread {
            try {
                val out = generate()
                pendingBytes = out.bytes
                pendingFileName = out.fileName
                pendingMime = out.mime
                runOnUiThread {
                    setBusy(false)
                    resultView.text = "已生成：${out.fileName}\nSHA-256 指纹：${out.fingerprint}\n${out.passwordNote}\n正在打开保存位置…"
                    createDocumentRequest.launch(out.fileName)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    resultView.text = "生成失败：${e.message}"
                    Toast.makeText(this, "生成失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun generatePreviewOnly() {
        setBusy(true)
        thread {
            try {
                val out = generate()
                runOnUiThread {
                    setBusy(false)
                    resultView.text = "已生成（未保存）：${out.fileName}\nSHA-256 指纹：${out.fingerprint}\n${out.passwordNote}\n点“一键生成并下载”可保存文件。"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    resultView.text = "生成失败：${e.message}"
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        // Buttons stay enabled; generation runs on a worker thread.
    }

    private data class GeneratedCert(
        val bytes: ByteArray,
        val fileName: String,
        val mime: String,
        val fingerprint: String,
        val passwordNote: String,
    )

    private fun generate(): GeneratedCert {
        val typeIndex = typeSpinner.selectedItemPosition
        val keyChoice = keySpinner.selectedItem.toString()
        val alias = aliasInput.text.toString().ifBlank { "iceraven" }
        val cn = cnInput.text.toString().ifBlank { "Iceraven" }
        val org = orgInput.text.toString().ifBlank { "22085" }
        val years = yearsInput.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 10
        var password = passwordInput.text.toString()
        val passwordNote: String
        if (password.isBlank()) {
            password = randomPassword()
            passwordNote = "密码（已自动生成，请记好）：$password"
        } else {
            passwordNote = "密码：已按你输入的密码生成"
        }

        val keyPair = generateKeyPair(keyChoice)
        val cert = selfSignedCert(keyPair, cn, org, years)
        val fingerprint = sha256Hex(cert.encoded)

        return when (typeIndex) {
            1 -> {
                val bytes = storeKeystore("JKS", keyPair, cert, alias, password)
                GeneratedCert(bytes, "$alias.jks", "application/x-java-keystore", fingerprint, passwordNote)
            }
            2 -> {
                val certPem = toPem(cert)
                val keyPem = toPem(keyPair.private)
                val combined = (certPem + keyPem).toByteArray(Charsets.UTF_8)
                GeneratedCert(combined, "$alias.pem", "application/x-pem-file", fingerprint, passwordNote)
            }
            3 -> {
                GeneratedCert(toPem(cert).toByteArray(Charsets.UTF_8), "$alias.crt", "application/x-x509-ca-cert", fingerprint, passwordNote)
            }
            else -> {
                val bytes = storeKeystore("PKCS12", keyPair, cert, alias, password)
                GeneratedCert(bytes, "$alias.p12", "application/x-pkcs12", fingerprint, passwordNote)
            }
        }
    }

    private fun generateKeyPair(choice: String): KeyPair {
        return when (choice) {
            "RSA 4096" -> KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
            "EC P-256" -> KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            "EC P-384" -> KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp384r1"))
            }.generateKeyPair()
            else -> KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        }
    }

    private fun selfSignedCert(keyPair: KeyPair, cn: String, org: String, years: Int): X509Certificate {
        val now = Date()
        val notAfter = Date(now.time + years.toLong() * 365L * 24L * 60L * 60L * 1000L)
        val serial = BigInteger(64, SecureRandom())
        val dn = X500Name("CN=$cn, O=$org")
        val sigAlg = if (keyPair.private.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
        val builder = X509v3CertificateBuilder(
            dn,
            serial,
            now,
            notAfter,
            dn,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        )
        val signer = JcaContentSignerBuilder(sigAlg).build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun storeKeystore(
        type: String,
        keyPair: KeyPair,
        cert: Certificate,
        alias: String,
        password: String,
    ): ByteArray {
        val ks = KeyStore.getInstance(type)
        ks.load(null, null)
        ks.setKeyEntry(alias, keyPair.private, password.toCharArray(), arrayOf(cert))
        val out = ByteArrayOutputStream()
        ks.store(out, password.toCharArray())
        return out.toByteArray()
    }

    private fun toPem(obj: Any): String {
        val writer = StringWriter()
        JcaPEMWriter(writer).use { it.writeObject(obj) }
        return writer.toString()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(":") { "%02X".format(it) }
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(9)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP).replace("/", "x").replace("+", "y")
    }
}
