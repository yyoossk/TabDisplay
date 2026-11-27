using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace TabDisplay;

/// <summary>
/// PC から出ている音を取る（WASAPI のループバック）。タブレット何台に配っても取るのは 1 つ。
/// 出す形：48kHz などそのままの速さ・2ch・16bit PCM（リトルエンディアン）
/// </summary>
public static class AudioHub
{
    private static readonly object Lock = new();
    private static readonly List<Action<byte[]>> Subscribers = new();
    private static WasapiLoopbackCapture? _cap;
    public static int Rate { get; private set; } = 48000;
    public const int Channels = 2;

    public static void Subscribe(Action<byte[]> a)
    {
        lock (Lock)
        {
            Subscribers.Add(a);
            if (_cap == null) StartCapture();
        }
    }

    public static void Unsubscribe(Action<byte[]> a)
    {
        lock (Lock)
        {
            Subscribers.Remove(a);
            if (Subscribers.Count == 0) StopCapture();
        }
    }

    private static void StartCapture()
    {
        try
        {
            var cap = new WasapiLoopbackCapture();
            var fmt = cap.WaveFormat;
            Rate = fmt.SampleRate;
            int ch = fmt.Channels;
            int bits = fmt.BitsPerSample;
            bool isFloat = fmt.Encoding == WaveFormatEncoding.IeeeFloat ||
                           (fmt.Encoding == WaveFormatEncoding.Extensible && bits == 32);
            Log.Write($"audio: {fmt.Encoding} {Rate}Hz {ch}ch {bits}bit");
            cap.DataAvailable += (_, e) =>
            {
                var pcm = Convert(e.Buffer, e.BytesRecorded, ch, bits, isFloat);
                if (pcm.Length == 0) return;
                Action<byte[]>[] subs;
                lock (Lock) subs = Subscribers.ToArray();
                foreach (var s in subs) s(pcm);
            };
            cap.RecordingStopped += (_, e) => { if (e.Exception != null) Log.Write("audio stopped: " + e.Exception.Message); };
            cap.StartRecording();
            _cap = cap;
        }
        catch (Exception e)
        {
            Log.Write("audio start: " + e.Message);
        }
    }

    private static void StopCapture()
    {
        try { _cap?.StopRecording(); } catch { }
        try { _cap?.Dispose(); } catch { }
        _cap = null;
    }

    /// <summary>どんな形でも 2ch・16bit にする（3ch 以上は前の左右だけ使う）</summary>
    private static byte[] Convert(byte[] buf, int len, int ch, int bits, bool isFloat)
    {
        int bytesPer = bits / 8;
        int frame = bytesPer * ch;
        if (frame == 0) return Array.Empty<byte>();
        int frames = len / frame;
        var outp = new byte[frames * 4];
        for (int f = 0; f < frames; f++)
        {
            int o = f * frame;
            short l = Sample(buf, o, bits, isFloat);
            short r = ch > 1 ? Sample(buf, o + bytesPer, bits, isFloat) : l;
            outp[f * 4] = (byte)l;
            outp[f * 4 + 1] = (byte)(l >> 8);
            outp[f * 4 + 2] = (byte)r;
            outp[f * 4 + 3] = (byte)(r >> 8);
        }
        return outp;
    }

    private static short Sample(byte[] b, int o, int bits, bool isFloat)
    {
        if (isFloat && bits == 32)
        {
            float v = BitConverter.ToSingle(b, o);
            return (short)Math.Clamp((int)(v * 32767f), -32768, 32767);
        }
        return bits switch
        {
            16 => BitConverter.ToInt16(b, o),
            24 => (short)((b[o + 2] << 8) | b[o + 1]),
            32 => (short)(BitConverter.ToInt32(b, o) >> 16),
            _ => 0,
        };
    }
}
