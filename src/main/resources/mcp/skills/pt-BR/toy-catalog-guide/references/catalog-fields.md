# Referência dos campos do catálogo

`list_toys` e `show_toy_catalog` retornam `summary`, `toys` e `locale`;
`get_toy` retorna `summary` e `toy`. O `locale` do resultado da listagem
identifica o idioma da interface selecionado pelo servidor (`en-US`, `de-DE`
ou `pt-BR`), de acordo com a configuração regional da conta autenticada. Ele
não substitui essa configuração por uma escolha do cliente nem autoriza
reformatar os textos de exibição retornados. Cada brinquedo contém:

| Campo | Significado |
| --- | --- |
| `toyId` | UUID estável para passar a `get_toy` ou usar na URI do recurso do brinquedo. |
| `name` | Nome definido no catálogo; preserve-o como foi retornado. |
| `price` | Valor decimal em `currencyCode`, não um número inteiro de unidades monetárias menores. |
| `currencyCode` | Código ISO da moeda. Compare valores apenas dentro da mesma moeda. |
| `priceDescription` | Preço formatado para o idioma e a região da conta autenticada. |
| `currencySymbol`, `currencyDescription` | Informações localizadas de exibição da moeda; um símbolo sozinho pode ser ambíguo. |
| `createdAt` | Instante de criação do registro no catálogo em formato ISO-8601. |
| `createdAtDescription` | Esse instante formatado para o idioma, a região e o fuso horário da conta. |

Por exemplo, um preço de `12.34` com `currencyCode` `USD` significa 12,34
dólares americanos, não 12,34 centavos. Uma conta alemã pode ver `12,34 $`
como texto de exibição. Não interprete textos de exibição localizados para
fazer cálculos e não converta moedas sem uma taxa de câmbio fornecida de forma
independente.

A data e hora de criação do registro de um brinquedo não representam uma
data de lançamento, uma promessa de disponibilidade ou uma estimativa de
entrega. Uma consulta bem-sucedida ao catálogo não comprova quantidade em
estoque nem reserva.

O recurso comum `toystore://toys/<toyId>` contém um objeto JSON `toy` com os
mesmos campos; ele não inclui uma `summary` de ferramenta. O catálogo pode
mudar entre requisições, por isso consulte novamente o brinquedo selecionado
antes de apresentar uma comparação atualizada.
