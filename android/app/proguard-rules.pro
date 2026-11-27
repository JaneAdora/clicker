# R8 rules for the release build.
#
# The Rust core is reached through UniFFI's generated Kotlin bindings, which call
# libclicker_ffi.so through JNA. JNA works by reflection: it binds the methods of
# the generated Library interface by name, reads and writes Structure fields by
# name in the order given by @Structure.FieldOrder, and finds the single method of
# each Callback interface reflectively. Renaming or removing any of these breaks
# the bridge at run time, so they are kept whole.

# JNA itself (rules from JNA's FAQ, "JNA on Android", widened to subpackages).
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# The generated UniFFI bindings (package uniffi.clicker_ffi).
-keep class uniffi.** { *; }

# @Structure.FieldOrder is read at run time; keep the annotations, generic
# signatures and inner-class data JNA's reflection relies on.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
