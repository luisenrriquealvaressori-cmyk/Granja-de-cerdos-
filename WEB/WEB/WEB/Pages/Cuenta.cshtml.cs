using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Security;

namespace WEB.Pages;

/// <summary>Perfil del usuario y cambio de contraseña (obligatorio en el primer ingreso).</summary>
public class CuentaModel : PageModel
{
    private readonly AuthService _auth;

    public CuentaModel(AuthService auth) => _auth = auth;

    [BindProperty] public string PasswordActual { get; set; } = string.Empty;
    [BindProperty] public string PasswordNueva { get; set; } = string.Empty;
    [BindProperty] public string PasswordConfirmar { get; set; } = string.Empty;

    [BindProperty(SupportsGet = true)] public bool Obligatorio { get; set; }

    public string? ErrorMessage { get; set; }
    public string? SuccessMessage { get; set; }

    public void OnGet()
    {
        Obligatorio = Obligatorio || User.FindFirstValue(CustomClaims.DebeCambiarPassword) == "1";
    }

    public async Task<IActionResult> OnPostCambiarPasswordAsync()
    {
        Obligatorio = User.FindFirstValue(CustomClaims.DebeCambiarPassword) == "1";

        var userId = User.GetUserId();
        if (userId is null) return RedirectToPage("/Login");

        if (PasswordNueva != PasswordConfirmar)
        {
            ErrorMessage = "La confirmación no coincide con la nueva contraseña.";
            return Page();
        }

        var (ok, error) = await _auth.CambiarPasswordAsync(userId.Value, PasswordActual, PasswordNueva,
            HttpContext.Connection.RemoteIpAddress?.ToString());
        if (!ok)
        {
            ErrorMessage = error;
            return Page();
        }

        // Renueva la cookie con el claim actualizado para liberar el bloqueo de "cambio obligatorio"
        var claims = User.Claims.Where(c => c.Type != CustomClaims.DebeCambiarPassword).ToList();
        claims.Add(new Claim(CustomClaims.DebeCambiarPassword, "0"));
        var identity = new ClaimsIdentity(claims, CookieAuthenticationDefaults.AuthenticationScheme, ClaimTypes.Name, ClaimTypes.Role);
        await HttpContext.SignInAsync(CookieAuthenticationDefaults.AuthenticationScheme, new ClaimsPrincipal(identity),
            new AuthenticationProperties { IsPersistent = false, ExpiresUtc = DateTimeOffset.UtcNow.AddHours(8) });

        TempData["Toast"] = "Contraseña actualizada correctamente.";
        return RedirectToPage("/Index");
    }
}
