<#
    生成安卓壳的启动图标与启动页位图。

    为什么要有这个脚本、而不是把 PNG 直接提交进仓库：
      1) 图标只有一份真源 —— frontend/images/icon-512.png，H5 的 PWA 图标和 APK 的启动图标
         必须是同一个图形；两处各存一份，迟早变成"手机上和网页上不是一个 logo"。
      2) 五档密度 + 圆形蒙版 + 自适应前景层一共 15 个文件，手工导出的结果不可复现；
         脚本是可复现的，android-check.sh 也因此可以放心断言"五档齐全"。
      3) 源图右下角带着 "Qoder AI 生成" 的水印（在白色留白区里），脚本按内容包围盒裁掉它 ——
         带水印的图当启动图标是上架事故，不是审美问题。

    用法：powershell -NoProfile -ExecutionPolicy Bypass -File tools/gen-android-icons.ps1
    依赖：Windows 自带的 System.Drawing（不需要装任何东西）。
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$src   = Join-Path $root 'frontend/images/icon-512.png'
$res   = Join-Path $root 'android/app/src/main/res'
if (-not (Test-Path $src))  { throw "找不到源图标：$src" }
if (-not (Test-Path $res))  { throw "找不到资源目录：$res" }

function New-Bitmap([int]$w, [int]$h) {
    $b = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($b)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    return @($b, $g)
}

function Save-Png($bitmap, $path) {
    $dir = Split-Path -Parent $path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    if (Test-Path $path) { Remove-Item $path -Force }
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
}

# ---------- 1. 读源图，按内容包围盒裁掉白边与右下角水印 ----------
$source = New-Object System.Drawing.Bitmap $src
$step = 2                                            # 采样步长：1024x1024 全扫太慢，2 像素精度足够
$minX = $source.Width; $minY = $source.Height
$maxX = -1; $maxY = -1
for ($y = 0; $y -lt $source.Height; $y += $step) {
    $rowLeft = -1; $rowRight = -1
    for ($x = 0; $x -lt $source.Width; $x += $step) {
        $c = $source.GetPixel($x, $y)
        if ($c.R -gt 245 -and $c.G -gt 245 -and $c.B -gt 245) { continue }   # 白/近白 = 背景
        if ($rowLeft -lt 0) { $rowLeft = $x }
        $rowRight = $x
    }
    if ($rowLeft -lt 0) { continue }
    # 水印只在右下角那一小条里：它那一行的"最左非白像素"远在图中央偏右，用它把这种行整行剔掉
    if ($rowLeft -gt ($source.Width * 0.35)) { continue }
    if ($rowLeft -lt $minX) { $minX = $rowLeft }
    if ($rowRight -gt $maxX) { $maxX = $rowRight }
    if ($y -lt $minY) { $minY = $y }
    if ($y -gt $maxY) { $maxY = $y }
}
if ($maxX -lt 0) { throw '源图整张都是白的，裁不出内容' }
# 列方向再收一次：水印在最后一列附近，按同样的道理用"每列最上的非白像素"排除
$colTop = @{}
for ($x = $minX; $x -le $maxX; $x += $step) {
    $top = -1
    for ($y = $minY; $y -le $maxY; $y += $step) {
        $c = $source.GetPixel($x, $y)
        if ($c.R -gt 245 -and $c.G -gt 245 -and $c.B -gt 245) { continue }
        $top = $y; break
    }
    $colTop[$x] = $top
}
foreach ($x in @($colTop.Keys)) {
    if ($colTop[$x] -lt 0) { continue }
    if ($colTop[$x] -gt ($source.Height * 0.35)) { $colTop.Remove($x) }   # 同上：只属于水印的列
}
if ($colTop.Count) {
    $minX = [Math]::Min($minX, ($colTop.Keys | Measure-Object -Minimum).Minimum)
    $maxX = [Math]::Max($maxX, ($colTop.Keys | Measure-Object -Maximum).Maximum)
}
$cropW = $maxX - $minX + 1
$cropH = $maxY - $minY + 1
$side = [Math]::Min($cropW, $cropH)
Write-Host ("内容包围盒 x{0}..{1} y{2}..{3}（{4}x{5}），取正方形边长 {6}" -f $minX, $maxX, $minY, $maxY, $cropW, $cropH, $side)
# 取正方形时居中，避免把圆角切掉一边
$sx = $minX + [int][Math]::Floor(($cropW - $side) / 2)
$sy = $minY + [int][Math]::Floor(($cropH - $side) / 2)
$art = New-Bitmap $side $side
[void]$art[1].DrawImage($source, (New-Object System.Drawing.Rectangle 0, 0, $side, $side),
    (New-Object System.Drawing.Rectangle $sx, $sy, $side, $side), [System.Drawing.GraphicsUnit]::Pixel)
$art[1].Dispose()
$artImg = $art[0]                                      # 后面所有绘制都用这张裁好的位图

# ---------- 2. 五档启动图标（方 + 圆 + 自适应前景） ----------
$dens = [ordered]@{ mdpi = 48; hdpi = 72; xhdpi = 96; xxhdpi = 144; xxxhdpi = 192 }
# 自适应图标是 108dp，其中安全区只有中间 66dp：前景按 108/66 反推放大，图形本体缩到 66%
$fgDens = [ordered]@{ mdpi = 108; hdpi = 162; xhdpi = 216; xxhdpi = 324; xxxhdpi = 432 }
$made = @()
foreach ($d in $dens.Keys) {
    $px = $dens[$d]
    $dir = Join-Path $res "mipmap-$d"

    $sq = New-Bitmap $px $px
    $sq[1].DrawImage($artImg, (New-Object System.Drawing.Rectangle 0, 0, $px, $px))
    $sq[1].Dispose()
    Save-Png $sq[0] (Join-Path $dir 'ic_launcher.png')
    $made += "mipmap-$d/ic_launcher.png ($px)"

    $rd = New-Bitmap $px $px
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse(0, 0, $px, $px)
    $rd[1].SetClip($path)
    $rd[1].DrawImage($artImg, (New-Object System.Drawing.Rectangle 0, 0, $px, $px))
    $path.Dispose()
    $rd[1].Dispose()
    Save-Png $rd[0] (Join-Path $dir 'ic_launcher_round.png')
    $made += "mipmap-$d/ic_launcher_round.png ($px 圆形蒙版)"

    $fp = $fgDens[$d]
    $inner = [int][Math]::Round($fp * (66.0 / 108.0))     # 落在安全区里，不会被启动器裁成椭圆
    $off = [int][Math]::Floor(($fp - $inner) / 2)
    $fg = New-Bitmap $fp $fp
    $fg[1].DrawImage($artImg, (New-Object System.Drawing.Rectangle $off, $off, $inner, $inner))
    $fg[1].Dispose()
    Save-Png $fg[0] (Join-Path $dir 'ic_launcher_foreground.png')
    $made += "mipmap-$d/ic_launcher_foreground.png ($fp，图形 $inner 居中)"
}

# ---------- 3. 启动页：沿用工程里原有那批尺寸，只换内容 ----------
# Capacitor 的 SplashScreen 用 CENTER_CROP 读 drawable-(port|land)-<密度>/splash.png，
# 尺寸是它自己定的，这里逐个文件量原尺寸再画回去，免得改了分辨率把启动页拉伸变形。
$bg = [System.Drawing.Color]::FromArgb(255, 15, 44, 77)   # 与 --bg / theme-color / capacitor 配置同色
$splashFiles = Get-ChildItem -Path $res -Recurse -Filter 'splash.png' |
    Where-Object { $_.FullName -match 'drawable(-port-|-land-|-)?[a-z0-9]*' }
foreach ($f in $splashFiles) {
    $old = New-Object System.Drawing.Bitmap $f.FullName
    $w = $old.Width; $h = $old.Height
    $old.Dispose()
    $cv = New-Bitmap $w $h
    $cv[1].Clear($bg)
    $target = [int][Math]::Round([Math]::Min($w, $h) * 0.42)
    $ox = [int][Math]::Floor(($w - $target) / 2)
    $oy = [int][Math]::Floor(($h - $target) / 2)
    $cv[1].DrawImage($artImg, (New-Object System.Drawing.Rectangle $ox, $oy, $target, $target))
    $cv[1].Dispose()
    Save-Png $cv[0] $f.FullName
    $made += ($f.FullName.Substring($res.Length + 1) + " ($w x $h)")
}
$artImg.Dispose()
$source.Dispose()

# ---------- 4. 自适应图标的底色跟图形底色对齐 ----------
$bgXml = Join-Path $res 'values/ic_launcher_background.xml'
@"
<?xml version="1.0" encoding="utf-8"?>
<!-- 由 tools/gen-android-icons.ps1 同步：与前端皮肤的深色底同值，
     否则圆角/圆形蒙版下会露出一圈与图标不符的白边。
     （注意：XML 注释里不能出现连续两个减号，所以这里不写变量名。） -->
<resources>
    <color name="ic_launcher_background">#0F2C4D</color>
</resources>
"@ | Set-Content -Path $bgXml -Encoding UTF8
$made += 'values/ic_launcher_background.xml (#0F2C4D)'

Write-Host ''
Write-Host ("已生成 {0} 个文件：" -f $made.Count)
$made | ForEach-Object { Write-Host "  · $_" }
