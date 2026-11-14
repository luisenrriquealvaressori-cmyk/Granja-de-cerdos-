using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Microsoft.IdentityModel.Tokens;

namespace WEB.Security;

/// <summary>Configuración JWT. La clave sale de configuración/entorno; si falta se genera una efímera (tokens se invalidan al reiniciar).</summary>
public class JwtSettings
{
    public string Issuer { get; init; } = "agropig";
    public string Audience { get; init; } = "agropig-clients";
    public int ExpireHours { get; init; } = 168; // 7 días: operarios en campo con conectividad intermitente
    public SymmetricSecurityKey SigningKey { get; init; } = default!;
    public bool ClaveEfimera { get; init; }

    public static JwtSettings From(IConfiguration config)
    {
        var key = config["Jwt:Key"];
        var efimera = string.IsNullOrWhiteSpace(key) || Encoding.UTF8.GetByteCount(key) < 32;
        var bytes = efimera ? RandomNumberGenerator.GetBytes(48) : Encoding.UTF8.GetBytes(key!);
        return new JwtSettings
        {
            Issuer = config["Jwt:Issuer"] ?? "agropig",
            Audience = config["Jwt:Audience"] ?? "agropig-clients",
            ExpireHours = int.TryParse(config["Jwt:ExpireHours"], out var h) && h > 0 ? h : 168,
            SigningKey = new SymmetricSecurityKey(bytes),
            ClaveEfimera = efimera
        };
    }
}

public class JwtTokenService
{
    private readonly JwtSettings _settings;
    public JwtTokenService(JwtSettings settings) => _settings = settings;

    public static List<Claim> CrearClaims(AuthUser u)
    {
        var claims = new List<Claim>
        {
            new(ClaimTypes.NameIdentifier, u.Id.ToString()),
            new(ClaimTypes.Name, u.Nombre),
            new(ClaimTypes.Email, u.Email),
            new(ClaimTypes.Role, u.Rol),
            new(CustomClaims.DebeCambiarPassword, u.DebeCambiarPassword ? "1" : "0")
        };
        if (!string.IsNullOrWhiteSpace(u.Matricula)) claims.Add(new(CustomClaims.Matricula, u.Matricula));
        if (!string.IsNullOrWhiteSpace(u.Cargo)) claims.Add(new(CustomClaims.Cargo, u.Cargo));
        if (u.GranjaId.HasValue) claims.Add(new(CustomClaims.GranjaId, u.GranjaId.Value.ToString()));
        return claims;
    }

    public (string Token, DateTime ExpiraUtc) Emitir(AuthUser u)
    {
        var expira = DateTime.UtcNow.AddHours(_settings.ExpireHours);
        var jwt = new JwtSecurityToken(
            issuer: _settings.Issuer,
            audience: _settings.Audience,
            claims: CrearClaims(u),
            notBefore: DateTime.UtcNow,
            expires: expira,
            signingCredentials: new SigningCredentials(_settings.SigningKey, SecurityAlgorithms.HmacSha256));
        return (new JwtSecurityTokenHandler().WriteToken(jwt), expira);
    }
}
