# -*- coding: utf-8 -*-
"""不依赖 Android Studio / Gradle，直接用 JDK + Android build-tools 构建可安装的 APK。

用法：
    python build_apk.py
    （或双击 build-apk.bat）

工具链默认在 D:/grades-track-sdk，也可用环境变量 GT_SDK_DIR 指定。
目录结构：
    <SDK>/jdk/jdk-17.0.13+11/bin/{java,javac,keytool}.exe
    <SDK>/build-tools/android-15/{aapt2,zipalign}.exe 与 lib/{d8,apksigner}.jar
    <SDK>/platform-35/android-35/android.jar
"""
import os, sys, io, zipfile, subprocess, shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent
WORK = ROOT / 'build'
DIST = ROOT / 'dist'
APP = ROOT / 'app' / 'src' / 'main'
SDK = Path(os.environ.get('GT_SDK_DIR', 'D:/grades-track-sdk'))
OUT_NAME = 'FamilyHub-v2.10.0.apk'
KS_PASS = 'gradestrack2026'

def need(p, label):
    if not Path(p).exists():
        raise SystemExit('缺少%s: %s\n请先运行 prepare_sdk 或设置 GT_SDK_DIR' % (label, p))
    return str(p)

def run(cmd, label, allow_fail=False):
    print('  >', label)
    r = subprocess.run([str(x) for x in cmd], stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
    if r.returncode != 0:
        print(r.stdout[-2500:])
        if not allow_fail:
            raise SystemExit('构建失败于：' + label)
        return False
    if r.stdout.strip():
        print('   ', r.stdout.strip()[-400:])
    return True

def prepare():
    """若只有 zip，自动解压（与 prepare 下载环节配套）。"""
    if not SDK.exists():
        return
    mapping = [('jdk17.zip', 'jdk'), ('build-tools.zip', 'build-tools'), ('platform-35.zip', 'platform-35')]
    for z, d in mapping:
        src, dst = SDK / z, SDK / d
        if src.exists() and not dst.exists():
            print('解压', src.name)
            with zipfile.ZipFile(src) as f:
                f.extractall(dst)

def main():
    prepare()
    jdk_dirs = sorted(SDK.glob('jdk/jdk-*'))
    jdk = Path(jdk_dirs[0]) if jdk_dirs else SDK / 'jdk'
    java = need(jdk / 'bin' / 'java.exe', 'java')
    javac = need(jdk / 'bin' / 'javac.exe', 'javac')
    keytool = need(jdk / 'bin' / 'keytool.exe', 'keytool')
    bt = SDK / 'build-tools' / 'android-15'
    aapt2 = need(bt / 'aapt2.exe', 'aapt2')
    zipalign = need(bt / 'zipalign.exe', 'zipalign')
    d8jar = need(bt / 'lib' / 'd8.jar', 'd8.jar')
    signer = need(bt / 'lib' / 'apksigner.jar', 'apksigner.jar')
    android_jar = need(SDK / 'platform-35' / 'android-35' / 'android.jar', 'android.jar')
    icon = need(APP / 'res' / 'mipmap' / 'ic_launcher.png', '应用图标')
    manifest = need(APP / 'AndroidManifest.xml', 'AndroidManifest.xml')

    if WORK.exists():
        shutil.rmtree(WORK)
    WORK.mkdir(parents=True)
    DIST.mkdir(exist_ok=True)
    (WORK / 'classes').mkdir()
    (WORK / 'dex').mkdir()
    (WORK / 'gen').mkdir()

    print('\n[1/7] 编译资源')
    res_zip = WORK / 'res.zip'
    run([aapt2, 'compile', '--dir', APP / 'res', '-o', res_zip], 'aapt2 compile')

    print('\n[2/7] 链接 APK 骨架')
    base = WORK / 'base.apk'
    run([aapt2, 'link', '-o', base, '-I', android_jar, '--manifest', manifest,
         '--min-sdk-version', '26', '--target-sdk-version', '35',
         '--version-code', '210', '--version-name', '2.10.0',
         '--java', WORK / 'gen', res_zip], 'aapt2 link')

    print('\n[3/7] 编译 Java')
    src = APP / 'java' / 'cn' / 'lyuan' / 'gradestrack' / 'MainActivity.java'
    javac_cmd = [javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                 '-bootclasspath', android_jar, '-classpath', android_jar,
                 '-d', WORK / 'classes', src]
    if not run(javac_cmd, 'javac (bootclasspath)', allow_fail=True):
        print('   回退：不带 bootclasspath 重新编译')
        run([javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8',
             '-classpath', android_jar, '-d', WORK / 'classes', src], 'javac (classpath)')
    classes = sorted((WORK / 'classes').rglob('*.class'))
    if not classes:
        raise SystemExit('没有生成 .class 文件')

    print('\n[4/7] 转 DEX')
    run([java, '-cp', d8jar, 'com.android.tools.r8.D8', '--lib', android_jar,
         '--min-api', '26', '--output', WORK / 'dex'] + classes, 'd8')
    dex = WORK / 'dex' / 'classes.dex'
    if not dex.exists():
        raise SystemExit('未生成 classes.dex')

    print('\n[5/7] 合并 DEX 并对齐')
    withdex = WORK / 'with-dex.apk'
    shutil.copy2(base, withdex)
    with zipfile.ZipFile(withdex, 'a', zipfile.ZIP_DEFLATED) as z:
        z.write(dex, 'classes.dex')
    aligned = WORK / 'aligned.apk'
    run([zipalign, '-f', '4', withdex, aligned], 'zipalign')

    print('\n[6/7] 生成签名并签名')
    ks = ROOT / 'grades-track.keystore'
    if not ks.exists():
        run([keytool, '-genkeypair', '-keystore', ks, '-alias', 'grades',
             '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
             '-storepass', KS_PASS, '-keypass', KS_PASS,
             '-dname', 'CN=GradesTrack, OU=Home, O=Family, L=Xian, ST=Shaanxi, C=CN',
             '-storetype', 'PKCS12'], 'keytool')
    out = DIST / OUT_NAME
    if out.exists():
        out.unlink()
    sign_cmd = [java, '-jar', signer, 'sign',
                '--ks', ks, '--ks-key-alias', 'grades',
                '--ks-pass', 'pass:' + KS_PASS, '--key-pass', 'pass:' + KS_PASS,
                '--min-sdk-version', '26', '--v1-signing-enabled', 'true',
                '--v2-signing-enabled', 'true', '--out', out, aligned]
    if not run(sign_cmd, 'apksigner sign', allow_fail=True):
        print('   回退：-cp 方式调用 apksigner')
        run([java, '-cp', signer, 'com.android.apksigner.ApkSignerTool', 'sign',
             '--ks', ks, '--ks-key-alias', 'grades',
             '--ks-pass', 'pass:' + KS_PASS, '--key-pass', 'pass:' + KS_PASS,
             '--min-sdk-version', '26', '--out', out, aligned], 'apksigner sign (cp)')

    print('\n[7/7] 校验签名')
    verify_cmd = [java, '-jar', signer, 'verify', '--verbose', out]
    if not run(verify_cmd, 'apksigner verify', allow_fail=True):
        run([java, '-cp', signer, 'com.android.apksigner.ApkSignerTool', 'verify', out], 'verify (cp)')

    print('\n完成：%s （%.2f MB）' % (out, out.stat().st_size / 1048576.0))
    print('安装：adb install -r "%s"' % out)
    print('或把 APK 复制到手机直接点开安装（需允许「安装未知应用」）。')

if __name__ == '__main__':
    main()
