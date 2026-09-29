import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
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

class ImportPage extends StatefulWidget {
  const ImportPage({super.key, required this.api});
  final ApiClient api;

  @override
  State<ImportPage> createState() => _ImportPageState();
}

class _ImportPageState extends State<ImportPage> {
  final text = TextEditingController();
  String? result;

  @override
  Widget build(BuildContext context) {
    return Page(title: 'Т-Банк', children: [
      const Text('Вставь текст SMS/push от Т-Банка. Android notification listener будет следующим этапом.'),
      TextField(controller: text, minLines: 4, maxLines: 8, decoration: const InputDecoration(labelText: 'Текст уведомления')),
      FilledButton(onPressed: importText, child: const Text('Импортировать')),
      if (result != null) Text(result!),
    ]);
  }

  Future<void> importText() async {
    final response = await widget.api.call('import/tbank-notification', method: 'POST', body: {'text': text.text});
    setState(() => result = 'Создана операция #${response['id']}');
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
      DropdownButtonFormField<String>(value: visibility, decoration: const InputDecoration(labelText: 'Видимость по умолчанию'), items: const [
        DropdownMenuItem(value: 'private', child: Text('Личное')),
        DropdownMenuItem(value: 'shared', child: Text('Общее')),
        DropdownMenuItem(value: 'amount_only', child: Text('Только сумма')),
      ], onChanged: (value) => setState(() => visibility = value!)),
      DropdownButtonFormField<String>(value: split, decoration: const InputDecoration(labelText: 'Разделение по умолчанию'), items: const [
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
