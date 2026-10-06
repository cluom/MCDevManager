using System.Security.Cryptography;
using System.Text;

namespace MCDevManager.Widgets;

public sealed class SecureStore
{
    // UserProfile 不受 MSIX AppData 虚拟化影响；导入工具与包内 COM 进程读取同一目录。
    public static readonly string DefaultRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".mcdevmanager-widgets");
    private readonly string root;
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("MCDevManager-WindowsWidgets-v1");
    public SecureStore(string? directory = null) { root = directory ?? DefaultRoot; Directory.CreateDirectory(root); }
    public T? Read<T>(string name)
    {
        var path = Path.Combine(root, name + ".dat");
        if (!File.Exists(path)) return default;
        var plaintext = ProtectedData.Unprotect(File.ReadAllBytes(path), Entropy, DataProtectionScope.CurrentUser);
        try { return WidgetJson.Decode<T>(Encoding.UTF8.GetString(plaintext)); }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
    }
    public void Write<T>(string name, T data)
    {
        var plaintext = Encoding.UTF8.GetBytes(WidgetJson.Encode(data));
        byte[] ciphertext;
        try { ciphertext = ProtectedData.Protect(plaintext, Entropy, DataProtectionScope.CurrentUser); }
        finally { CryptographicOperations.ZeroMemory(plaintext); }
        var temporary = Path.Combine(root, name + "." + Guid.NewGuid().ToString("N") + ".tmp");
        File.WriteAllBytes(temporary, ciphertext);
        File.Move(temporary, Path.Combine(root, name + ".dat"), true);
    }
    public SharedAccount[] Accounts() => Read<AccountSnapshot>("accounts")?.Accounts ?? [];
    public void Import(AccountSnapshot snapshot)
    {
        if (snapshot.Accounts.Length > 100 || snapshot.Accounts.Select(x => x.Id).Distinct().Count() != snapshot.Accounts.Length)
            throw new InvalidDataException("账号快照格式异常");
        foreach (var account in snapshot.Accounts)
        {
            if (!WidgetSettings.IsId(account.Id) || account.Name.Length > 200) throw new InvalidDataException("账号格式异常");
            _ = NeteaseApi.CookieHeader(account.Cookies);
        }
        Write("accounts", snapshot);
        Diagnostic($"session_import accounts={snapshot.Accounts.Length}");
    }
    public WidgetData? Cache(string widgetId) => Read<WidgetData>("cache-" + Hash(widgetId));
    public void Cache(string widgetId, WidgetData data) => Write("cache-" + Hash(widgetId), data);
    public void DeleteCache(string widgetId) => File.Delete(Path.Combine(root, "cache-" + Hash(widgetId) + ".dat"));
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
    public void Diagnostic(string message)
    {
        // 调用者只传类型、状态、数量和耗时，禁止传异常 Message、URL 查询串、账号名或 Cookie。
        try
        {
            var path = Path.Combine(root, "diagnostics.log");
            lock (LogLock)
            {
                if (File.Exists(path) && new FileInfo(path).Length > 512_000) File.Move(path, path + ".previous", true);
                File.AppendAllText(path, $"{DateTimeOffset.Now:O} {message}\n", Encoding.UTF8);
            }
        }
        catch (IOException) { }
    }
    private static readonly object LogLock = new();
}
