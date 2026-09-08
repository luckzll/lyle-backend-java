param(
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$Destination
)
# Mechanical size normalization before Tianai cuts its fixed-size puzzle template.
Add-Type -AssemblyName System.Drawing
$sourceImage = [System.Drawing.Image]::FromFile((Resolve-Path -LiteralPath $Source))
$canvas = [System.Drawing.Bitmap]::new(600, 360)
$graphics = [System.Drawing.Graphics]::FromImage($canvas)
try {
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.DrawImage($sourceImage, 0, 0, 600, 360)
    $canvas.Save([System.IO.Path]::GetFullPath($Destination), [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $graphics.Dispose()
    $canvas.Dispose()
    $sourceImage.Dispose()
}
