namespace TabDisplay;

/// <summary>名前を 1 行入れてもらう小さな窓</summary>
public static class TextPrompt
{
    public static string? Ask(IWin32Window owner, string title, string label, string initial = "")
    {
        using var f = new Form
        {
            Text = title,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            StartPosition = FormStartPosition.CenterParent,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
        };
        var col = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, Padding = new Padding(12) };
        var box = new TextBox { Width = 300, Text = initial };
        var ok = new Button { Text = "OK", DialogResult = DialogResult.OK };
        col.Controls.Add(new Label { Text = label, AutoSize = true });
        col.Controls.Add(box);
        col.Controls.Add(ok);
        f.Controls.Add(col);
        f.AcceptButton = ok;
        return f.ShowDialog(owner) == DialogResult.OK && box.Text.Trim().Length > 0 ? box.Text.Trim() : null;
    }
}
