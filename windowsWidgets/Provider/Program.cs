using System.Runtime.InteropServices;
using Microsoft.Windows.Widgets.Providers;
using WinRT;

namespace MCDevManager.Widgets;

[ComImport, ComVisible(false), InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("00000001-0000-0000-C000-000000000046")]
internal interface IClassFactory
{
    [PreserveSig] int CreateInstance(IntPtr outer, ref Guid iid, out IntPtr instance);
    [PreserveSig] int LockServer(bool locked);
}
[ComVisible(true)]
internal sealed class ProviderFactory : IClassFactory
{
    public int CreateInstance(IntPtr outer, ref Guid iid, out IntPtr instance)
    {
        instance = IntPtr.Zero;
        if (outer != IntPtr.Zero) return unchecked((int)0x80040110);
        try
        {
            // QueryInterface 而不是只匹配 IUnknown，支持宿主请求 Provider2。
            var unknown = MarshalInspectable<IWidgetProvider>.FromManaged(new WidgetProvider());
            try { return Marshal.QueryInterface(unknown, ref iid, out instance); }
            finally { Marshal.Release(unknown); }
        }
        catch (Exception e) { return Marshal.GetHRForException(e); }
    }
    public int LockServer(bool locked) => 0;
}
internal static class Program
{
    private static readonly Guid ClassId = new("A1E7D097-354C-4B6A-8BB1-F42A3877B596");
    [DllImport("ole32.dll")] private static extern int CoInitializeEx(IntPtr reserved, uint flags);
    [DllImport("ole32.dll")] private static extern void CoUninitialize();
    [DllImport("ole32.dll")] private static extern int CoRegisterClassObject([MarshalAs(UnmanagedType.LPStruct)] Guid clsid,
        [MarshalAs(UnmanagedType.IUnknown)] object factory, uint context, uint flags, out uint cookie);
    [DllImport("ole32.dll")] private static extern int CoRevokeClassObject(uint cookie);
    [MTAThread]
    private static int Main(string[] args)
    {
        var store = new SecureStore();
        try
        {
            if (args.Contains("--import-sessions"))
            {
                using var mutex = new Mutex(false, "Local\\MCDevManagerWidgetSessionImport");
                if (!mutex.WaitOne(TimeSpan.FromSeconds(10))) return 2;
                try
                {
                    using var input = new StreamReader(Console.OpenStandardInput(), System.Text.Encoding.UTF8);
                    var buffer = new char[524_289]; var count = input.ReadBlock(buffer, 0, buffer.Length);
                    if (count >= buffer.Length) return 3;
                    store.Import(WidgetJson.Decode<AccountSnapshot>(new string(buffer, 0, count)));
                }
                finally { mutex.ReleaseMutex(); }
                return 0;
            }
            if (args.Contains("--clear-sessions")) { store.Import(new AccountSnapshot([])); return 0; }
            if (args.Contains("--generate-assets")) { PreviewAssets.Generate(args[^1]); return 0; }
            Marshal.ThrowExceptionForHR(CoInitializeEx(IntPtr.Zero, 0));
            var factory = new ProviderFactory();
            Marshal.ThrowExceptionForHR(CoRegisterClassObject(ClassId, factory, 4, 1, out var cookie));
            store.Diagnostic("provider_started schema=1");
            try { using var wait = new ManualResetEvent(false); wait.WaitOne(); }
            finally { _ = CoRevokeClassObject(cookie); GC.KeepAlive(factory); CoUninitialize(); }
            return 0;
        }
        catch (Exception e) { store.Diagnostic("provider_failed type=" + e.GetType().Name); return 1; }
    }
}
