using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Globalization;

namespace MCDevManager.Widgets;

public static class ChartRenderer
{
    private static readonly Color[] Colors = [Color.FromArgb(143, 120, 240), Color.FromArgb(68, 195, 203),
        Color.FromArgb(241, 174, 71), Color.FromArgb(224, 113, 151), Color.FromArgb(111, 203, 132),
        Color.FromArgb(82, 158, 238), Color.FromArgb(212, 150, 240), Color.FromArgb(204, 186, 95),
        Color.FromArgb(180, 195, 210), Color.FromArgb(237, 124, 93), Color.FromArgb(107, 183, 164)];
    public static string ColorHex(int index) => $"#{Colors[index % Colors.Length].R:X2}{Colors[index % Colors.Length].G:X2}{Colors[index % Colors.Length].B:X2}";
    public static byte[] Render(MetricSeries[] series, bool aggregate, int height = 540)
    {
        const int width = 720;
        using var bitmap = new Bitmap(width, height);
        using var g = Graphics.FromImage(bitmap);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Color.FromArgb(25, 25, 29));
        using var label = new Font("Microsoft YaHei UI", 17, FontStyle.Regular, GraphicsUnit.Pixel);
        using var legend = new Font("Microsoft YaHei UI", 19, FontStyle.Regular, GraphicsUnit.Pixel);
        using var white = new SolidBrush(Color.FromArgb(230, 228, 238));
        using var subtle = new SolidBrush(Color.FromArgb(156, 152, 168));
        using var grid = new Pen(Color.FromArgb(55, 53, 64));
        var relative = ChartScale.IsRelative(series);
        g.DrawString(relative ? "相对趋势 · 各线周期峰值=100" : "原始数值 · " + (series.Length > 0 ? Metric.Find(series[0].MetricId).Unit : ""), label, subtle, 12, 8);
        var allPoints = series.Select(s => ChartScale.PlotPoints(s, relative)).ToArray();
        var numbers = allPoints.SelectMany(x => x).Where(x => x.Value.HasValue).Select(x => x.Value!.Value).ToArray();
        var minimum = Math.Min(0, numbers.DefaultIfEmpty(0).Min());
        var maximum = Math.Max(relative ? 100 : 1, numbers.DefaultIfEmpty(1).Max());
        var legendRows = (series.Length + 1) / 2;
        var area = new RectangleF(70, 48, width - 92, Math.Max(95, height - 98 - legendRows * 48));
        for (var i = 0; i <= 4; i++)
        {
            var y = area.Bottom - i * area.Height / 4;
            g.DrawLine(grid, area.Left, y, area.Right, y);
            g.DrawString((minimum + i * (maximum - minimum) / 4).ToString("0.#", CultureInfo.InvariantCulture), label, subtle, 6, y - 10);
        }
        for (var s = 0; s < series.Length; s++)
        {
            var points = allPoints[s];
            using var line = new Pen(Colors[s % Colors.Length], 3.5f);
            using var dot = new SolidBrush(Colors[s % Colors.Length]);
            PointF? previous = null;
            for (var i = 0; i < points.Length; i++)
            {
                if (points[i].Value is not double value) { previous = null; continue; }
                var p = new PointF(area.Left + i * area.Width / Math.Max(1, points.Length - 1),
                    area.Bottom - (float)((value - minimum) / (maximum - minimum)) * area.Height);
                if (previous is PointF last) g.DrawLine(line, last, p);
                g.FillEllipse(dot, p.X - 2.5f, p.Y - 2.5f, 5, 5);
                previous = p;
            }
            var x = 14 + (s % 2) * 355;
            var y = area.Bottom + 42 + (s / 2) * 48;
            g.DrawLine(line, x, y + 12, x + 22, y + 12);
            var metric = Metric.Find(series[s].MetricId);
            var latest = series[s].Points.LastOrDefault()?.Value;
            var peak = series[s].Points.Where(p => p.Value.HasValue).Select(p => Math.Abs(p.Value!.Value)).DefaultIfEmpty(0).Max();
            g.DrawString(metric.Label(aggregate) + ": " + metric.Format(latest), legend, white, x + 28, y);
            if (relative) g.DrawString("峰值 " + metric.Format(peak), label, subtle, x + 28, y + 24);
        }
        if (series.FirstOrDefault()?.Points is { Length: > 0 } dates)
        {
            g.DrawString(dates[0].Date.ToString("MM-dd"), label, subtle, area.Left, area.Bottom + 7);
            g.DrawString(dates[^1].Date.ToString("MM-dd"), label, subtle, area.Right - 50, area.Bottom + 7);
        }
        if (numbers.Length == 0) g.DrawString("暂无数据（不补零）", legend, white, 270, area.Top + area.Height / 2);
        using var output = new MemoryStream();
        bitmap.Save(output, ImageFormat.Png);
        return output.ToArray();
    }
    public static string DataUri(MetricSeries[] series, bool aggregate, int height) =>
        "data:image/png;base64," + Convert.ToBase64String(Render(series, aggregate, height));
}
