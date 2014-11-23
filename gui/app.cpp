
#include <boost/bind.hpp>

#include <QDebug>

#include "app.hpp"

void avimApp::on_post(std::function<void()> qfunc_ptr)
{
    (qfunc_ptr)();
}

avimApp::avimApp(int argc, char* argv[])
    : QApplication(argc, argv)
{
    qRegisterMetaType<std::function<void()>>("std::function<void()>");
    connect(this, SIGNAL(post(std::function<void()>)),
            this, SLOT(on_post(std::function<void()>)), Qt::QueuedConnection);
    // 开启 boost 线程跑 io_service
    m_io_thread = std::thread(boost::bind(&boost::asio::io_service::run, &m_io_service));

	// 发送一个测试 信号, 看能不能顺利的在2个线程里同步

	m_io_service.post(
		[this](){
			emit post([](){
				qDebug() << "test GUI thread and IO thread interactivity" ;
			});
		}
	);
}

bool avimApp::load_key_and_cert(std::string cur_key, std::string cur_cert)
{
    if (cur_key.empty())
    {
        fs::path avim_key = get_app_dir();

        avim_key /= "user.key";
        cur_key = avim_key.string();
    }

    if (cur_cert.empty())
    {
        fs::path avim_cert = get_app_dir();

        avim_cert /= "user.cert";
        cur_key = avim_cert.string();
    }

    if (!fs::exists(fs::path(cur_key)) || !fs::exists(fs::path(cur_cert)))
    {
        std::cout << cur_key << '\n';
        std::cout << cur_cert << '\n';
        std::cout << "omg, key and cert not exist";
        return false;
    }
    qDebug() << "cert:" << QString::fromStdString(cur_cert);
    qDebug() << "key:" << QString::fromStdString(cur_key);

    // 登录进去
    m_avim_client.reset(new avim_client(m_io_service, cur_key, cur_key));
    return true;
}

int avimApp::exec()
{
    load_cfg();

    std::string auto_login = m_cfg->get<std::string>("global.auto_login");

    if (auto_login == "true")
    {
        if (load_key_and_cert(m_cfg->get<std::string>("global.key"), m_cfg->get<std::string>("global.cert")))
        {
			return start_main();
        }
        else
        {
            m_login_dialog.reset(new login_dialog(m_cfg.get()));
            if (m_login_dialog->exec() == QDialog::Accepted)
            {
                if (!load_key_and_cert(m_login_dialog->get_key_path(), m_login_dialog->get_cert_path()))
                {
                    return 1;
                }
                m_login_dialog.reset();
                return start_main();
            }
            else
            {
                return 0;
            }
        }
    }
    else
    {
        m_login_dialog.reset(new login_dialog(m_cfg.get()));
        if (m_login_dialog->exec() == QDialog::Accepted)
        {
            if (!load_key_and_cert(m_login_dialog->get_key_path(), m_login_dialog->get_cert_path()))
            {
                return 1;
            }
            m_login_dialog.reset();
            return start_main();
        }
        else
        {
            return 0;
        }
    }
}

void avimApp::load_cfg()
{
    fs::path appdatadir = get_app_dir();

    if (!fs::exists(appdatadir))
        fs::create_directories(appdatadir);

    m_cfg.reset(new avim::ini(appdatadir / "config.ini"));
}

int avimApp::start_main()
{
    // 创建主窗口, 开始真正的 GUI 之旅
    m_mainwindow.reset(new MainWindow);
    m_mainwindow->show();
    return QApplication::exec();
}
