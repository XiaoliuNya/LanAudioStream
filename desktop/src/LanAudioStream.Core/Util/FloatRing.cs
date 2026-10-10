namespace LanAudioStream.Core.Util;

public sealed class FloatRing
{
    private readonly float[] _buf;
    private readonly object _gate = new();
    private int _tail;
    private int _count;

    public FloatRing(int capacity)
    {
        _buf = new float[capacity];
    }

    public int Capacity => _buf.Length;

    public int Count
    {
        get
        {
            lock (_gate) return _count;
        }
    }

    public long DroppedSamples { get; private set; }

    public void Write(ReadOnlySpan<float> data)
    {
        lock (_gate)
        {
            foreach (var s in data)
            {
                if (_count == _buf.Length)
                {
                    _tail = (_tail + 1) % _buf.Length;
                    _count--;
                    DroppedSamples++;
                }
                _buf[(_tail + _count) % _buf.Length] = s;
                _count++;
            }
            Monitor.PulseAll(_gate);
        }
    }

    public int Read(float[] dst, int need)
    {
        lock (_gate)
        {
            int n = Math.Min(need, _count);
            for (int i = 0; i < n; i++)
            {
                dst[i] = _buf[_tail];
                _tail = (_tail + 1) % _buf.Length;
            }
            _count -= n;
            return n;
        }
    }

    public bool WaitFor(int need, int timeoutMs, Func<bool> abort)
    {
        lock (_gate)
        {
            if (_count < need && !abort())
            {
                Monitor.Wait(_gate, timeoutMs);
            }
            return _count >= need;
        }
    }

    public void Clear()
    {
        lock (_gate)
        {
            _count = 0;
            _tail = 0;
        }
    }
}
