# kotlinx.serialization ships its own consumer rules. BouncyCastle (Ed25519 in :core) is used through
# its lightweight API only; silence warnings for the optional classes it references.
-dontwarn org.bouncycastle.**

# Keep line numbers for stack traces that a user may send us; message texts are never logged.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
