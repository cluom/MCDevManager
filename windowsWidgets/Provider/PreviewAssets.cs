using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

namespace MCDevManager.Widgets;

public static class PreviewAssets
{
    public static void Generate(string directory)
    {
        Directory.CreateDirectory(directory);
        foreach (var size in new[] { 44, 50, 150 })
        {
            using var bitmap = new Bitmap(size, size);
            using var g = Graphics.FromImage(bitmap);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.FromArgb(84, 59, 149));
            using var diamond = new SolidBrush(Color.FromArgb(76, 211, 188));
            g.FillPolygon(diamond, new PointF[] { new(size * .5f, size * .2f), new(size * .8f, size * .45f),
                new(size * .5f, size * .8f), new(size * .2f, size * .45f) });
            bitmap.Save(Path.Combine(directory, $"Logo{size}.png"), ImageFormat.Png);
        }
        var end = new DateOnly(2026, 10, 5);
        var series = new[] { "diamond", "cnt_buy", "DAU" }.Select((metric, s) => new MetricSeries(metric,
            Enumerable.Range(0, 14).Select(d => new Point(end.AddDays(d - 13), (double?)(50 + d * 3 + Math.Sin(d * .7 + s) * 24) * (s == 0 ? 300 : s == 2 ? 8 : 1))).ToArray())).ToArray();
        // 虚构预览数据，不读取或公开真实账号信息。
        File.WriteAllBytes(Path.Combine(directory, "TrendPreview.png"), ChartRenderer.Render(series, true));
        using var overview = new Bitmap(720, 540);
        using var canvas = Graphics.FromImage(overview);
        canvas.Clear(Color.FromArgb(25, 25, 29));
        using var title = new Font("Microsoft YaHei UI", 25, FontStyle.Bold, GraphicsUnit.Pixel);
        using var value = new Font("Microsoft YaHei UI", 34, FontStyle.Bold, GraphicsUnit.Pixel);
        using var hint = new Font("Microsoft YaHei UI", 21, FontStyle.Regular, GraphicsUnit.Pixel);
        using var white = new SolidBrush(Color.FromArgb(237, 234, 243));
        using var gray = new SolidBrush(Color.FromArgb(176, 168, 190));
        using var card = new SolidBrush(Color.FromArgb(37, 34, 45));
        canvas.DrawString("MCDevManager · 总览示意", title, white, 24, 20);
        string[] titles = ["本月收益 · 钻石", "本月下载", "昨日收益 · 钻石", "昨日下载"];
        string[] values = ["2,458,660", "44,576", "314,334", "7,101"];
        string[] hints = ["上月 7,037,583", "上月 209,826", "14日均 374,670", "14日均 6,876"];
        for (var i = 0; i < 4; i++)
        {
            var x = 20 + (i % 2) * 350; var y = 85 + (i / 2) * 180;
            canvas.FillRectangle(card, x, y, 330, 165);
            canvas.DrawString(titles[i], hint, gray, x + 16, y + 14);
            canvas.DrawString(values[i], value, white, x + 16, y + 57);
            canvas.DrawString(hints[i], hint, gray, x + 16, y + 118);
        }
        canvas.DrawString("预览示意 · 实际布局由 Windows 渲染", hint, gray, 24, 475);
        overview.Save(Path.Combine(directory, "OverviewPreview.png"), ImageFormat.Png);
    }
}
