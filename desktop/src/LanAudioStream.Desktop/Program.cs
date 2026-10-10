namespace LanAudioStream.App;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        if (args.Contains("--headless"))
        {
            Environment.Exit(Headless.Run(args));
            return;
        }
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        try
        {
            Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        }
        catch
        {
            // ignored
        }
        Application.ThreadException += (_, e) => ReportFatal(e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => ReportFatal(e.ExceptionObject as Exception);

        if (args.Contains("--dumpui"))
        {
            using var form = new UI.MainForm();
            form.Show(); // 触发句柄创建 -> DPI 缩放生效
            Application.DoEvents();
            Application.DoEvents();
            var dump = form.DumpLayout();
            var path = Path.Combine(AppContext.BaseDirectory, "layout-dump.txt");
            File.WriteAllText(path, dump, System.Text.Encoding.UTF8);
            Console.WriteLine(dump);
            return;
        }

        Application.Run(new UI.MainForm());
    }

    private static void ReportFatal(Exception? ex)
    {
        try
        {
            var path = Path.Combine(AppContext.BaseDirectory, "crash.log");
            File.AppendAllText(path, $"[{DateTime.Now:O}] {ex}\r\n");
        }
        catch
        {
            // ignored
        }
        MessageBox.Show(ex?.ToString() ?? "未知错误", "LanAudioStream 错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }
}
