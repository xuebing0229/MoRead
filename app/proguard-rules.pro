# Tink 在 androidx.security-crypto 中引用的 Error Prone 类型全是源码级注解，运行时
# 不参与密钥处理；依赖 POM 未把它们带进 APK，R8 需要显式知道可以安全忽略。
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# PDFBox-Android only calls Gemalto's optional JPEG 2000 codec when a document
# contains JPX images. It is not bundled by PDFBox and is unrelated to the
# permission-encryption normalization used by MoRead.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
