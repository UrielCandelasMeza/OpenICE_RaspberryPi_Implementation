# Estructura de la recepcion de la configuracion en tiempo real

Para realizar esta configuracion sigue una secuencia de:

1. 2 bytes (Data code)
2. 8 bytes (Interval ms)
3. 5 bytes (Min)
4. 5 bytes (Max)
5. 3 bytes (Max Bin)

## Fragmentos de informacion

Para esta demostracion vamos a usar un log obtenido de Draeger atlan:

``` txt
00   20000-  21  121FFF01   20000- 181  181FFF05   20000    0  100FFF06   20000    0  120FFF07   20000    0   20FFF08   20000    0  100FFF0A   20000    0   20FFF2A   20000    0   20FFF
```

El primer fragmento esta definido como: `00   20000-  21  121FFF` y gracias a la informacion anterior podemos concluir que:

1. 00 (Data code)
2. 20000 (Interval ms)
3. -21 (Min)
4. 121 (Max)
5. FFF (Max Bin)

Por lo que en realidad seria visto como: `00 20000 -21 121 FFF`

Ya acomodando el resto de los fragmentos se veria algo asi:

```txt
00 20000 -21 121 FFF
01 20000 -181 181 FFF
05 20000 0 100 FFF
06 20000 0 120 FFF
07 20000 0 20 FFF
08 20000 0 100 FFF
0A 20000 0 20 FFF
2A 20000 0 20 FFF
```

> Que haya espacios donde aparentemente no deberia se debe a que tiene que cumplir con todos los bytes necesarios

Otra cosa que aclarar es que cada caracter es un byte. el 00 son 2 bytes ascii

