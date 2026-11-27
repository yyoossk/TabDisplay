; TabDisplay のインストーラー（Inno Setup 6）
; ビルド: ISCC.exe /DSrcDir=..\out\TabDisplay /DAppVersion=1.0.0 TabDisplay.iss

#ifndef SrcDir
  #define SrcDir "..\out\TabDisplay"
#endif
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{6B0C3F5E-2D7A-4E8B-9C1F-7A3D5E9B1C24}
AppName=TabDisplay
AppVersion={#AppVersion}
AppPublisher=yyoossk
AppPublisherURL=https://github.com/yyoossk/tabdisplay
DefaultDirName={autopf}\TabDisplay
DefaultGroupName=TabDisplay
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\out
OutputBaseFilename=TabDisplay-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\TabDisplay.exe
CloseApplications=yes
ShowLanguageDialog=auto

[Languages]
Name: "ja"; MessagesFile: "compiler:Languages\Japanese.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
ja.TaskDriver=仮想ディスプレイ（タブレット用の画面）も入れる
en.TaskDriver=Also install the virtual display (the screen for the tablet)
ja.TaskStartup=Windows の起動時に開く（右下のアイコンにしまった状態で）
en.TaskStartup=Open when Windows starts (minimized to the tray icon)
ja.Installing=仮想ディスプレイを入れています…
en.Installing=Installing the virtual display…
ja.Removing=仮想ディスプレイを消しています…
en.Removing=Removing the virtual display…

[Tasks]
Name: "driver"; Description: "{cm:TaskDriver}"
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; Flags: unchecked
Name: "startup"; Description: "{cm:TaskStartup}"; Flags: unchecked

[Files]
Source: "{#SrcDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\TabDisplay"; Filename: "{app}\TabDisplay.exe"
Name: "{group}\{cm:UninstallProgram,TabDisplay}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\TabDisplay"; Filename: "{app}\TabDisplay.exe"; Tasks: desktopicon

[Registry]
; Windows の起動時に開く（ソフトの設定画面と同じ場所・同じ値）
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "TabDisplay"; ValueData: """{app}\TabDisplay.exe"" --autostart"; Tasks: startup; Flags: uninsdeletevalue

[Run]
Filename: "{app}\TabDisplay.exe"; Parameters: "--install-driver"; StatusMsg: "{cm:Installing}"; Tasks: driver; Flags: runhidden waituntilterminated
Filename: "{app}\TabDisplay.exe"; Description: "{cm:LaunchProgram,TabDisplay}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{app}\TabDisplay.exe"; Parameters: "--uninstall-driver"; StatusMsg: "{cm:Removing}"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveDriver"

[UninstallDelete]
Type: filesandordirs; Name: "{app}"
