using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Security;

namespace WEB.Pages;

[AllowAnonymous]
public class LoginModel : PageModel
{
    private readonly AuthService _auth;

    public LoginModel(AuthService auth)
    {
        _auth = auth;
    }

    [BindProperty]
    public string Email { get; set; } = string.Empty;

    [BindProperty]
    public string Password { get; set; } = string.Empty;

    [BindProperty]
    public bool Recordar { get; set; }

    [BindProperty(SupportsGet = true)]
    public string? ReturnUrl { get; set; }

    public string? ErrorMessage { get; set; }
    public string? SuccessMessage { get; set; }

    public IActionResult OnGet(string? logout = null, string? expirada = null)
    {
        // Si ya tiene sesión válida no vuelve a mostrar el login
        if (User.Identity?.IsAuthenticated == true && string.IsNullOrEmpty(logout))
        {
            return RedirectToPage("/Index");
        }

        if (!string.IsNullOrEmpty(logout))
            SuccessMessage = "Sesión cerrada correctamente.";
        else if (!string.IsNullOrEmpty(expirada))
            ErrorMessage = "Tu sesión expiró. Vuelve a iniciar sesión.";

        return Page();
    }

    public async Task<IActionResult> OnPostAsync()
    {
        if (string.IsNullOrWhiteSpace(Email) || string.IsNullOrWhiteSpace(Password))
        {
            ErrorMessage = "Ingresa tu nombre de usuario y tu contraseña.";
            return Page();
        }

        var ip = HttpContext.Connection.RemoteIpAddress?.ToString();
        var resultado = await _auth.ValidarAsync(Email, Password, ip);

        switch (resultado.Estado)
        {
            case LoginEstado.Ok:
                var usuario = resultado.Usuario!;
                var identity = new ClaimsIdentity(
                    JwtTokenService.CrearClaims(usuario),
                    CookieAuthenticationDefaults.AuthenticationScheme,
                    ClaimTypes.Name,
                    ClaimTypes.Role);

                await HttpContext.SignInAsync(
                    CookieAuthenticationDefaults.AuthenticationScheme,
                    new ClaimsPrincipal(identity),
                    new AuthenticationProperties
                    {
                        IsPersistent = Recordar,
                        ExpiresUtc = DateTimeOffset.UtcNow.AddHours(Recordar ? 24 * 7 : 8),
                        AllowRefresh = true
                    });

                if (usuario.DebeCambiarPassword)
                    return RedirectToPage("/Cuenta", new { obligatorio = true });

                // Solo se aceptan redirecciones locales (evita open-redirect)
                if (!string.IsNullOrEmpty(ReturnUrl) && Url.IsLocalUrl(ReturnUrl))
                    return LocalRedirect(ReturnUrl);

                return RedirectToPage("/Index");

            case LoginEstado.Bloqueado:
                var minutos = Math.Max(1, (int)Math.Ceiling((resultado.Espera ?? TimeSpan.FromMinutes(10)).TotalMinutes));
                ErrorMessage = $"Cuenta bloqueada temporalmente por intentos fallidos. Intenta de nuevo en {minutos} min.";
                break;

            case LoginEstado.Invalido:
                ErrorMessage = "Correo o contraseña incorrectos.";
                break;

            default:
                ErrorMessage = "El servicio de autenticación no está disponible. Intenta nuevamente en unos minutos.";
                break;
        }

        Password = string.Empty;
        return Page();
    }
}
