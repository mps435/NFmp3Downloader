
; Inno Setup Script for NFmp3Downloader - Final Professional Version

[Setup]
AppName=NFmp3Downloader
AppVersion=1.0.0
AppPublisher=mps
DefaultDirName={code:GetDefaultDir}
DefaultGroupName=NFdownload
OutputBaseFilename=nfmp3downloader-setup-1.0.0
PrivilegesRequired=admin
Compression=lzma
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\NFmp3Downloader.exe
ArchitecturesInstallIn64BitMode=x64

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "hebrew"; MessagesFile: "compiler:Languages\Hebrew.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}";

[Files]
Source: "release\NFmp3Downloader\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs
Source: "src\main\resources\web\*"; DestDir: "{app}\web\"; Flags: recursesubdirs

[Icons]
Name: "{group}\NFmp3Downloader"; Filename: "{app}\NFmp3Downloader.exe"
Name: "{group}\{cm:UninstallProgram,NFmp3Downloader}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\NFmp3Downloader"; Filename: "{app}\NFmp3Downloader.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\NFmp3Downloader.exe"; Description: "{cm:LaunchProgram,NFmp3Downloader}"; Flags: nowait postinstall skipifsilent

[Registry]
Root: HKA; Subkey: "Software\Classes\nfmp3downloader"; ValueType: string; ValueName: ""; ValueData: "URL:NFmp3Downloader Protocol"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\nfmp3downloader"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\nfmp3downloader\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\NFmp3Downloader.exe"" ""--protocol-launch"""

[UninstallDelete]
Type: filesandordirs; Name: "{userappdata}\NFmp3Downloader"

[Code]
function GetDefaultDir(Param: String): String;
begin
  if IsAdminInstallMode() then
    Result := ExpandConstant('{autopf}\NFmp3Downloader')
  else
    Result := ExpandConstant('{localappdata}\NFmp3Downloader');
end;
