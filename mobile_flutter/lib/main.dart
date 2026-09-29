import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:file_picker/file_picker.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  runApp(const FinPulseApp());
}

class FinPulseApp extends StatelessWidget {
  const FinPulseApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'FinPulse',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF0F766E),
          primary: const Color(0xFF0F766E),
          secondary: const Color(0xFFB9F66B),
          surface: Colors.white,
        ),
        useMaterial3: true,
        cardTheme: const CardThemeData(
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(8))),
        ),
        inputDecorationTheme: const InputDecorationTheme(
          border: OutlineInputBorder(borderRadius: BorderRadius.all(Radius.circular(8))),
        ),
      ),
      home: const AppRoot(),
    );
  }
}

class ApiClient {
  ApiClient(this.baseUrl, this.prefs);

  final String baseUrl;
  final SharedPreferences prefs;

  String? get token => prefs.getString('access_token');
  String? get refreshToken => prefs.getString('refresh_token');

  Future<dynamic> call(String path, {String method = 'GET', Object? body}) async {
    final uri = Uri.parse('$baseUrl/api/v1/$path');
    final headers = {'Content-Type': 'application/json'};
    final access = token;
    if (access != null) headers['Authorization'] = 'Bearer $access';
    final request = http.Request(method, uri);
    request.headers.addAll(headers);
    if (body != null) request.body = jsonEncode(body);
    final response = await http.Response.fromStream(await request.send());
    if (response.statusCode == 401 && refreshToken != null) {
      await refresh();
      return call(path, method: method, body: body);
    }
    if (response.statusCode >= 400) {
      throw Exception(response.body);
    }
    return response.body.isEmpty ? null : jsonDecode(response.body);
  }

  Map<String, String> authHeaders() {
    final headers = {'Content-Type': 'application/json'};
    final access = token;
    if (access != null) headers['Authorization'] = 'Bearer $access';
    return headers;
  }

  Future<Map<String, dynamic>> uploadBankStatement({required String filename, required List<int> bytes, bool retried = false}) async {
    final request = http.MultipartRequest('POST', Uri.parse('$baseUrl/api/v1/import/bank-statement'));
    final access = token;
    if (access != null) request.headers['Authorization'] = 'Bearer $access';
    request.files.add(http.MultipartFile.fromBytes('file', bytes, filename: filename));
    final response = await http.Response.fromStream(await request.send());
    if (response.statusCode == 401 && refreshToken != null && !retried) {
      await refresh();
      return uploadBankStatement(filename: filename, bytes: bytes, retried: true);
    }
    if (response.statusCode >= 400) {
      throw Exception(response.body);
    }
    return jsonDecode(response.body) as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> confirmImport(String previewId) async {
    return await call('import/confirm', method: 'POST', body: {'preview_id': previewId}) as Map<String, dynamic>;
  }

  Future<void> authenticate({
    required String username,
    required String password,
    String? displayName,
    String language = 'ru',
  }) async {
    final path = displayName == null ? 'auth/login' : 'auth/register';
    final body = {'username': username, 'password': password};
    if (displayName != null) {
      body['display_name'] = displayName;
      body['language'] = language;
    }
    final result = await call(path, method: 'POST', body: body) as Map<String, dynamic>;
    await prefs.setString('access_token', result['access_token']);
    await prefs.setString('refresh_token', result['refresh_token']);
  }

  Future<void> refresh() async {
    final response = await http.post(
      Uri.parse('$baseUrl/api/v1/auth/refresh'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'refresh_token': refreshToken}),
    );
    if (response.statusCode >= 400) {
      await logoutLocal();
      throw Exception(response.body);
    }
    final result = jsonDecode(response.body);
    await prefs.setString('access_token', result['access_token']);
    await prefs.setString('refresh_token', result['refresh_token']);
  }

  Future<void> logoutLocal() async {
    await prefs.remove('access_token');
    await prefs.remove('refresh_token');
  }
}

class AppRoot extends StatefulWidget {
  const AppRoot({super.key});

  @override
  State<AppRoot> createState() => _AppRootState();
}

class _AppRootState extends State<AppRoot> {
  SharedPreferences? prefs;
  String server = 'http://192.168.3.48:8000';

  @override
  void initState() {
    super.initState();
    SharedPreferences.getInstance().then((value) {
      setState(() {
        prefs = value;
        server = value.getString('server') ?? server;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    final ready = prefs;
    if (ready == null) return const Splash();
    final api = ApiClient(server, ready);
    return api.token == null
        ? LoginScreen(
            server: server,
            onServerChanged: (value) async {
              await ready.setString('server', value);
              setState(() => server = value);
            },
            onAuthenticated: () => setState(() {}),
            api: api,
          )
        : HomeScreen(api: api, onLogout: () async {
            await api.logoutLocal();
            setState(() {});
          });
  }
}

class Splash extends StatelessWidget {
  const Splash({super.key});

  @override
  Widget build(BuildContext context) {
    return const Scaffold(body: Center(child: CircularProgressIndicator()));
  }
}

class LoginScreen extends StatefulWidget {
  const LoginScreen({
    super.key,
    required this.server,
    required this.onServerChanged,
    required this.onAuthenticated,
    required this.api,
  });

  final String server;
  final ValueChanged<String> onServerChanged;
  final VoidCallback onAuthenticated;
  final ApiClient api;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final username = TextEditingController();
  final password = TextEditingController();
  final name = TextEditingController();
  late final server = TextEditingController(text: widget.server);
  bool register = false;
  bool busy = false;
  String? error;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF6F7F9),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            const SizedBox(height: 28),
            Row(children: [
              Container(
                width: 58,
                height: 58,
                decoration: BoxDecoration(color: Theme.of(context).colorScheme.primary, borderRadius: BorderRadius.circular(8)),
                child: const Icon(Icons.monitor_heart, color: Colors.white, size: 32),
              ),
              const SizedBox(width: 14),
              const Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('FINPULSE', style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 4)),
                Text('Домашний сервер. AI работает на твоём ПК.'),
              ])),
            ]),
            const SizedBox(height: 28),
            const Text('Деньги в ритме.', style: TextStyle(fontSize: 44, height: 1.02, fontWeight: FontWeight.w900)),
            const SizedBox(height: 10),
            const Text('Чеки, бюджеты, долги и Т-Банк в одном приложении.', style: TextStyle(fontSize: 18, color: Color(0xFF55525D))),
            const SizedBox(height: 24),
            Card(child: Padding(
              padding: const EdgeInsets.all(18),
              child: Column(children: [
                if (register) TextField(controller: name, decoration: const InputDecoration(labelText: 'Имя')),
                if (register) const SizedBox(height: 12),
                TextField(controller: username, decoration: const InputDecoration(labelText: 'Логин')),
                const SizedBox(height: 12),
                TextField(controller: password, obscureText: true, decoration: const InputDecoration(labelText: 'Пароль')),
                const SizedBox(height: 16),
                FilledButton(
                  onPressed: busy ? null : authenticate,
                  style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8))),
                  child: Text(register ? 'Создать аккаунт' : 'Войти'),
                ),
                TextButton(onPressed: () => setState(() => register = !register), child: Text(register ? 'У меня уже есть аккаунт' : 'Создать аккаунт')),
                if (busy) const LinearProgressIndicator(),
                if (error != null) Text(error!, style: const TextStyle(color: Colors.red)),
              ]),
            )),
            const SizedBox(height: 18),
            Card(child: Padding(
              padding: const EdgeInsets.all(18),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('Сервер', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
                const SizedBox(height: 8),
                const Text('ПК должен быть включён. Для доступа с улицы нужен проброс порта/HTTPS.'),
                const SizedBox(height: 12),
                TextField(controller: server, decoration: const InputDecoration(labelText: 'Адрес backend'), onChanged: widget.onServerChanged),
              ]),
            )),
          ],
        ),
      ),
    );
  }

  Future<void> authenticate() async {
    setState(() { busy = true; error = null; });
    try {
      await widget.api.authenticate(
        username: username.text.trim(),
        password: password.text,
        displayName: register ? name.text.trim() : null,
      );
      widget.onAuthenticated();
    } catch (failure) {
      setState(() => error = 'Не удалось войти. Проверь сервер, логин и пароль.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.api, required this.onLogout});

  final ApiClient api;
  final VoidCallback onLogout;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int tab = 0;

  @override
  Widget build(BuildContext context) {
    final pages = [
      PulsePage(api: widget.api),
      FinancePage(api: widget.api),
      ImportPage(api: widget.api),
      WorkspacePage(api: widget.api),
      ProfilePage(api: widget.api, onLogout: widget.onLogout),
    ];
    return Scaffold(
      body: pages[tab],
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (value) => setState(() => tab = value),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.monitor_heart), label: 'Пульс'),
          NavigationDestination(icon: Icon(Icons.account_balance_wallet_outlined), label: 'Деньги'),
          NavigationDestination(icon: Icon(Icons.add_circle_outline), label: 'Импорт'),
          NavigationDestination(icon: Icon(Icons.favorite_border), label: 'Пара'),
          NavigationDestination(icon: Icon(Icons.person_outline), label: 'Профиль'),
        ],
      ),
    );
  }
}

class PulsePage extends StatelessWidget {
  const PulsePage({super.key, required this.api});

  final ApiClient api;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder(
      future: api.call('pulse/today'),
      builder: (context, snapshot) {
        final data = snapshot.data as Map<String, dynamic>?;
        return Page(title: 'Пульс дня', children: [
          if (!snapshot.hasData) const LinearProgressIndicator(),
          if (data != null) ...[
            MetricCard(title: 'Можно тратить сегодня', value: '${data['daily_safe']} ₽', accent: true),
            MetricCard(title: 'Расходы месяца', value: '${data['month']['expenses']} ₽'),
            MetricCard(title: 'Прогноз', value: '${data['month']['forecast']} ₽'),
            const SizedBox(height: 8),
            const Text('Риски', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
            for (final risk in (data['risks'] as List)) Card(child: ListTile(title: Text(risk['title'].toString()), trailing: Text('${risk['amount'] ?? ''}'))),
          ],
        ]);
      },
    );
  }
}


class FinancePage extends StatefulWidget {
  const FinancePage({super.key, required this.api});
  final ApiClient api;

  @override
  State<FinancePage> createState() => _FinancePageState();
}

class _FinancePageState extends State<FinancePage> {
  final amount = TextEditingController();
  final description = TextEditingController();
  final budget = TextEditingController();
  final debtName = TextEditingController();
  final debtAmount = TextEditingController();
  String category = 'прочее';
  String txType = 'expense';
  int refreshKey = 0;
  bool busy = false;
  String? message;

  Future<Map<String, dynamic>> load() async {
    final values = await Future.wait([
      widget.api.call('categories'),
      widget.api.call('dashboard'),
      widget.api.call('budgets'),
      widget.api.call('debts'),
    ]);
    return {
      'categories': values[0],
      'dashboard': values[1],
      'budgets': values[2],
      'debts': values[3],
    };
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Map<String, dynamic>>(
      key: ValueKey(refreshKey),
      future: load(),
      builder: (context, snapshot) {
        final data = snapshot.data;
        final categories = (data?['categories'] as List<dynamic>? ?? ['прочее']).cast<String>();
        if (!categories.contains(category)) category = categories.first;
        final dashboard = data?['dashboard'] as Map<String, dynamic>?;
        final budgets = data?['budgets'] as Map<String, dynamic>?;
        final debts = data?['debts'] as List<dynamic>? ?? [];
        final recent = dashboard?['recent'] as List<dynamic>? ?? [];
        if (budget.text.isEmpty && budgets != null) budget.text = budgets['total'].toString();
        return Page(title: 'Деньги', children: [
          if (!snapshot.hasData) const LinearProgressIndicator(),
          if (dashboard != null) Row(children: [
            Expanded(child: MetricCard(title: 'Остаток', value: '${dashboard['remaining']} ₽', accent: true)),
            const SizedBox(width: 8),
            Expanded(child: MetricCard(title: 'Долги', value: '${dashboard['total_debt']} ₽')),
          ]),
          Card(child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('Новая операция', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 12),
              TextField(controller: amount, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Сумма')),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(initialValue: txType, decoration: const InputDecoration(labelText: 'Тип'), items: const [
                DropdownMenuItem(value: 'expense', child: Text('Расход')),
                DropdownMenuItem(value: 'income', child: Text('Доход')),
              ], onChanged: (value) => setState(() => txType = value!)),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(initialValue: category, decoration: const InputDecoration(labelText: 'Категория'), items: [
                for (final item in categories) DropdownMenuItem(value: item, child: Text(item)),
              ], onChanged: (value) => setState(() => category = value!)),
              const SizedBox(height: 12),
              TextField(controller: description, decoration: const InputDecoration(labelText: 'Описание')),
              const SizedBox(height: 12),
              FilledButton(onPressed: busy ? null : addTransaction, child: const Text('Сохранить')),
            ]),
          )),
          Card(child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('Бюджет месяца', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 12),
              TextField(controller: budget, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Лимит')),
              const SizedBox(height: 12),
              OutlinedButton(onPressed: busy ? null : saveBudget, child: const Text('Сохранить бюджет')),
            ]),
          )),
          Card(child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('Долги', style: Theme.of(context).textTheme.titleLarge),
              for (final debt in debts) ListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(debt['name'].toString()),
                subtitle: Text('Осталось ${debt['current_amount']} ₽ · платёж ${debt['min_payment']} ₽'),
              ),
              const SizedBox(height: 8),
              TextField(controller: debtName, decoration: const InputDecoration(labelText: 'Название долга')),
              const SizedBox(height: 8),
              TextField(controller: debtAmount, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: 'Сумма долга')),
              const SizedBox(height: 8),
              FilledButton(onPressed: busy ? null : addDebt, child: const Text('Добавить долг')),
            ]),
          )),
          const Text('Последние операции', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
          for (final tx in recent) Card(child: ListTile(
            title: Text(tx['description']?.toString().isEmpty == false ? tx['description'].toString() : tx['category'].toString()),
            subtitle: Text('${tx['created_at']} · ${tx['category']}'),
            trailing: Text('${tx['amount']} ₽'),
          )),
          if (busy) const LinearProgressIndicator(),
          if (message != null) Text(message!),
        ]);
      },
    );
  }

  double parsed(TextEditingController controller) => double.parse(controller.text.replaceAll(',', '.'));

  Future<void> addTransaction() async {
    await runAction(() async {
      await widget.api.call('transactions', method: 'POST', body: {
        'amount': parsed(amount),
        'category': category,
        'description': description.text,
        'tx_type': txType,
      });
      amount.clear();
      description.clear();
      message = 'Операция сохранена.';
    });
  }

  Future<void> saveBudget() async {
    await runAction(() async {
      await widget.api.call('budgets', method: 'PUT', body: {'total': parsed(budget), 'limits': {}, 'weekly_food': 0});
      message = 'Бюджет сохранён.';
    });
  }

  Future<void> addDebt() async {
    await runAction(() async {
      await widget.api.call('debts', method: 'POST', body: {
        'name': debtName.text,
        'current_amount': parsed(debtAmount),
        'interest_rate': 0,
        'min_payment': 0,
      });
      debtName.clear();
      debtAmount.clear();
      message = 'Долг добавлен.';
    });
  }

  Future<void> runAction(Future<void> Function() action) async {
    setState(() { busy = true; message = null; });
    try {
      await action();
      setState(() => refreshKey++);
    } catch (_) {
      setState(() => message = 'Не удалось сохранить. Проверь сумму и сервер.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }
}

class ImportPage extends StatefulWidget {
  const ImportPage({super.key, required this.api});
  final ApiClient api;

  @override
  State<ImportPage> createState() => _ImportPageState();
}

class _ImportPageState extends State<ImportPage> {
  final text = TextEditingController();
  String? result;
  Map<String, dynamic>? bankPreview;
  bool busy = false;

  @override
  Widget build(BuildContext context) {
    final preview = bankPreview;
    return Page(title: 'Импорт', children: [
      const Text('SMS/push от Т-Банка'),
      TextField(controller: text, minLines: 4, maxLines: 8, decoration: const InputDecoration(labelText: 'Текст уведомления')),
      FilledButton(onPressed: busy ? null : importText, child: const Text('Импортировать уведомление')),
      const Divider(height: 32),
      const Text('PDF-выписка Т-Банка', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
      const Text('Выбери справку о движении средств. Сервер покажет операции и дубли перед импортом.'),
      OutlinedButton.icon(onPressed: busy ? null : pickBankPdf, icon: const Icon(Icons.picture_as_pdf), label: const Text('Выбрать PDF')),
      if (preview != null) BankPreviewCard(preview: preview, onConfirm: busy ? null : confirmBankImport),
      if (busy) const LinearProgressIndicator(),
      if (result != null) Text(result!),
    ]);
  }

  Future<void> importText() async {
    setState(() { busy = true; result = null; });
    try {
      final response = await widget.api.call('import/tbank-notification', method: 'POST', body: {'text': text.text});
      setState(() => result = 'Создана операция #${response['id']}');
    } catch (_) {
      setState(() => result = 'Не удалось импортировать уведомление. Проверь текст и сервер.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> pickBankPdf() async {
    setState(() { busy = true; result = null; bankPreview = null; });
    try {
      final picked = await FilePicker.platform.pickFiles(type: FileType.custom, allowedExtensions: ['pdf'], withData: true);
      final file = picked?.files.single;
      final bytes = file?.bytes;
      if (file == null || bytes == null) {
        setState(() => result = 'PDF не выбран.');
        return;
      }
      final preview = await widget.api.uploadBankStatement(filename: file.name, bytes: bytes);
      setState(() {
        bankPreview = preview;
        result = 'Найдено операций: ${preview['operations'].length}. Проверь и подтверди импорт.';
      });
    } catch (_) {
      setState(() => result = 'Не удалось прочитать PDF. Нужна справка Т-Банка о движении средств.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> confirmBankImport() async {
    final previewId = bankPreview?['preview_id']?.toString();
    if (previewId == null) return;
    setState(() { busy = true; result = null; });
    try {
      final imported = await widget.api.confirmImport(previewId);
      setState(() {
        bankPreview = null;
        result = 'Импортировано: ${imported['imported']}. Пропущено дублей: ${imported['skipped']}.';
      });
    } catch (_) {
      setState(() => result = 'Импорт не подтверждён. Возможно, итоги PDF не сошлись.');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }
}

class BankPreviewCard extends StatelessWidget {
  const BankPreviewCard({super.key, required this.preview, required this.onConfirm});
  final Map<String, dynamic> preview;
  final VoidCallback? onConfirm;

  @override
  Widget build(BuildContext context) {
    final operations = preview['operations'] as List<dynamic>;
    final checkOk = preview['check_ok'] == true;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Предпросмотр PDF', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 8),
          Text('Расходы: ${preview['expenses']} ₽'),
          Text('Доходы: ${preview['income']} ₽'),
          Text('Дубли: ${preview['duplicates']}'),
          Text(checkOk ? 'Итоги банка сошлись' : 'Итоги банка не сошлись', style: TextStyle(color: checkOk ? Colors.green : Colors.red)),
          const SizedBox(height: 8),
          for (final op in operations.take(5)) Text('${op['date']} · ${op['merchant'] ?? op['description']} · ${op['amount']} ₽'),
          if (operations.length > 5) Text('И ещё ${operations.length - 5} операций'),
          const SizedBox(height: 12),
          FilledButton(onPressed: checkOk ? onConfirm : null, child: const Text('Подтвердить импорт')),
        ]),
      ),
    );
  }
}

class WorkspacePage extends StatefulWidget {
  const WorkspacePage({super.key, required this.api});
  final ApiClient api;

  @override
  State<WorkspacePage> createState() => _WorkspacePageState();
}

class _WorkspacePageState extends State<WorkspacePage> {
  String mode = 'solo';
  String visibility = 'private';
  String split = 'none';
  final partner = TextEditingController();
  bool loaded = false;

  @override
  void initState() {
    super.initState();
    widget.api.call('workspace').then((value) {
      final data = value as Map<String, dynamic>;
      setState(() {
        mode = data['mode'];
        visibility = data['default_visibility'];
        split = data['default_split'];
        partner.text = data['partner_name'] ?? '';
        loaded = true;
      });
    });
  }

  @override
  Widget build(BuildContext context) {
    return Page(title: 'Пара и приватность', children: [
      if (!loaded) const LinearProgressIndicator(),
      SegmentedButton<String>(
        segments: const [ButtonSegment(value: 'solo', label: Text('Один')), ButtonSegment(value: 'couple', label: Text('Пара'))],
        selected: {mode},
        onSelectionChanged: (value) => setState(() => mode = value.first),
      ),
      TextField(controller: partner, decoration: const InputDecoration(labelText: 'Имя партнёра')),
      DropdownButtonFormField<String>(initialValue: visibility, decoration: const InputDecoration(labelText: 'Видимость по умолчанию'), items: const [
        DropdownMenuItem(value: 'private', child: Text('Личное')),
        DropdownMenuItem(value: 'shared', child: Text('Общее')),
        DropdownMenuItem(value: 'amount_only', child: Text('Только сумма')),
      ], onChanged: (value) => setState(() => visibility = value!)),
      DropdownButtonFormField<String>(initialValue: split, decoration: const InputDecoration(labelText: 'Разделение по умолчанию'), items: const [
        DropdownMenuItem(value: 'none', child: Text('Не делить')),
        DropdownMenuItem(value: 'equal', child: Text('50/50')),
        DropdownMenuItem(value: 'percent', child: Text('Процентами')),
        DropdownMenuItem(value: 'manual', child: Text('Вручную')),
      ], onChanged: (value) => setState(() => split = value!)),
      FilledButton(onPressed: save, child: const Text('Сохранить')),
    ]);
  }

  Future<void> save() async {
    await widget.api.call('workspace', method: 'PUT', body: {
      'mode': mode,
      'default_visibility': visibility,
      'default_split': split,
      'partner_name': partner.text,
      'owner_share': 50,
    });
  }
}

class ProfilePage extends StatelessWidget {
  const ProfilePage({super.key, required this.api, required this.onLogout});
  final ApiClient api;
  final VoidCallback onLogout;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder(
      future: api.call('server/config'),
      builder: (context, snapshot) => Page(title: 'Профиль', children: [
        if (snapshot.data != null) MetricCard(title: 'Сервер', value: (snapshot.data as Map)['base_url'].toString()),
        OutlinedButton(onPressed: onLogout, child: const Text('Выйти')),
      ]),
    );
  }
}

class Page extends StatelessWidget {
  const Page({super.key, required this.title, required this.children});
  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Text(title, style: const TextStyle(fontSize: 32, fontWeight: FontWeight.w900)),
          const SizedBox(height: 18),
          ...children.map((child) => Padding(padding: const EdgeInsets.only(bottom: 12), child: child)),
        ],
      ),
    );
  }
}

class MetricCard extends StatelessWidget {
  const MetricCard({super.key, required this.title, required this.value, this.accent = false});
  final String title;
  final String value;
  final bool accent;

  @override
  Widget build(BuildContext context) {
    return Card(
      color: accent ? const Color(0xFFD9F99D) : Colors.white,
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(title, style: const TextStyle(color: Color(0xFF55525D))),
          const SizedBox(height: 8),
          Text(value, style: const TextStyle(fontSize: 30, fontWeight: FontWeight.w900)),
        ]),
      ),
    );
  }
}


