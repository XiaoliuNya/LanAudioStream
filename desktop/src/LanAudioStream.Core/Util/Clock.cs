using System.Diagnostics;

namespace LanAudioStream.Core.Util;

public static class Clock
{
    private static readonly double TicksToUs = 1_000_000.0 / Stopwatch.Frequency;

    public static ulong NowUs() => (ulong)(Stopwatch.GetTimestamp() * TicksToUs);

    public static double Ms(ulong fromUs, ulong toUs) => (toUs - fromUs) / 1000.0;
}
