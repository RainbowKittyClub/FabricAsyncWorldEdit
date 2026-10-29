{
  description = "Isolated native Minecraft 26.3 FAWE test environment";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/8c50a710ddca43d7a530fb805ad55bde8d0141c5";
  outputs = { self, nixpkgs }: let
    system = "x86_64-linux";
    pkgs = import nixpkgs { inherit system; };
    nativeLibraries = with pkgs; [
      sdl3 vulkan-loader mesa libGL libglvnd libx11 libxcursor libxrandr libxi
      libxext libxrender libxtst libxfixes libxkbcommon wayland alsa-lib libpulseaudio
      stdenv.cc.cc.lib flite systemdLibs
    ];
  in {
    devShells.${system}.default = pkgs.mkShell {
      packages = with pkgs; [
        jdk25 nodejs_24 python3 xorg-server xauth vulkan-tools mesa-demos
      ];
      JAVA_HOME = "${pkgs.jdk25}";
      LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath nativeLibraries;
      VK_DRIVER_FILES = "${pkgs.mesa}/share/vulkan/icd.d/lvp_icd.x86_64.json";
      LIBGL_ALWAYS_SOFTWARE = "1";
      SDL_VIDEO_DRIVER = "x11";
    };
  };
}
