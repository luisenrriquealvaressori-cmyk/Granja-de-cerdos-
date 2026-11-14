package com.example.porciapp.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/** Teclado numérico con decimales para montos, pesos y cantidades. */
val DecimalKeyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)

/** Interpreta "12,5" y "12.5" por igual (teclados en español usan coma). */
fun String.toDecimalOrNull(): Double? = trim().replace(',', '.').toDoubleOrNull()

/** Deja solo dígitos y un único separador decimal (punto o coma). */
fun filtrarDecimal(texto: String): String {
    val sb = StringBuilder()
    var haySeparador = false
    for (c in texto) {
        when {
            c.isDigit() -> sb.append(c)
            (c == '.' || c == ',') && !haySeparador -> { sb.append(c); haySeparador = true }
        }
    }
    return sb.toString()
}
