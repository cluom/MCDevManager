namespace MCDevManager.Widgets;

public static class ChartScale
{
    public static bool IsRelative(MetricSeries[] series) => series.Select(x => Metric.Find(x.MetricId).Unit).Distinct().Count() > 1;
    public static Point[] PlotPoints(MetricSeries series, bool relative)
    {
        var metric = Metric.Find(series.MetricId);
        var multiplier = !relative && metric.Percent ? 100d : 1d;
        if (relative)
        {
            var peak = series.Points.Where(x => x.Value.HasValue).Select(x => Math.Abs(x.Value!.Value)).DefaultIfEmpty(0).Max();
            multiplier = peak == 0 ? 1 : 100 / peak;
        }
        return series.Points.Select(x => x with { Value = x.Value * multiplier }).ToArray();
    }
}
